package gc.garcol.aeronauction.cluster;

import gc.garcol.aeronauction.protocol.AuctionPageReply;
import gc.garcol.aeronauction.protocol.AuctionReply;
import gc.garcol.aeronauction.protocol.AuctionView;
import gc.garcol.aeronauction.protocol.Codec;
import gc.garcol.aeronauction.protocol.Command;
import gc.garcol.aeronauction.protocol.sbe.ReplyStatus;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The replicated state machine. Everything that changes state happens in a callback and uses only the log's
 * timestamp, so all nodes stay identical. See section 5 of plan.md for the timer rules.
 */
public final class AuctionService implements ClusteredService
{
    static final long TICK_TIMER_ID = 1;

    private static final Logger LOG = LoggerFactory.getLogger(AuctionService.class);

    private static final int MAX_REPLY_ATTEMPTS = 1_000;

    private final AuctionBook book = new AuctionBook();
    private final AuctionTimers timers = new AuctionTimers();
    private final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer(Codec.MAX_MESSAGE_LENGTH);
    private final Codec codec = new Codec();
    private final NodeMetrics metrics;

    private Cluster cluster;
    private IdleStrategy idleStrategy;
    private boolean tickArmed;
    private long lastTimestamp;

    AuctionService(final NodeMetrics metrics)
    {
        this.metrics = metrics;
    }

    @Override
    public void onStart(final Cluster cluster, final Image snapshotImage)
    {
        this.cluster = cluster;
        this.idleStrategy = cluster.idleStrategy();

        if (snapshotImage != null)
        {
            loadSnapshot(snapshotImage);
        }
    }

    @Override
    public void onSessionOpen(final ClientSession session, final long timestamp)
    {
        lastTimestamp = timestamp;
    }

    @Override
    public void onSessionClose(final ClientSession session, final long timestamp, final CloseReason closeReason)
    {
        lastTimestamp = timestamp;
    }

    @Override
    public void onSessionMessage(
        final ClientSession session,
        final long timestamp,
        final DirectBuffer message,
        final int offset,
        final int length,
        final Header header)
    {
        lastTimestamp = timestamp;

        final Codec.Request request;
        try
        {
            request = codec.decodeRequest(message, offset, length);
        }
        catch (final IllegalArgumentException ex)
        {
            return; // malformed input must never take down the state machine
        }

        final long correlationId = request.correlationId();
        final int replyLength = switch (request.command())
        {
            case Command.CreateAuction c -> encode(correlationId, create(c, timestamp));
            case Command.PlaceBid c ->
            {
                final AuctionBook.Result bid = book.placeBid(c.auctionId(), c.bidderId(), c.amount(), timestamp);
                metrics.onBid(bid.status());
                yield encode(correlationId, bid);
            }
            case Command.GetAuction c -> encode(correlationId, book.get(c.auctionId(), timestamp));
            case Command.ListAuctions c ->
            {
                final AuctionBook.Page page = book.list(c.page(), c.size(), timestamp);
                final AuctionPageReply pageReply = new AuctionPageReply(
                    correlationId, page.status(), c.page(), c.size(), page.totalItems(), page.items());
                yield codec.encodePageReply(pageReply, buffer);
            }
        };

        reply(session, replyLength);
    }

    @Override
    public void onTimerEvent(final long correlationId, final long timestamp)
    {
        lastTimestamp = timestamp;

        if (correlationId != TICK_TIMER_ID)
        {
            return;
        }

        tickArmed = false;
        timers.advance(timestamp, auctionId ->
        {
            final AuctionView closed = book.close(auctionId);
            if (closed != null)
            {
                metrics.onAuctionClosed(closed, timestamp);
                LOG.info(
                    "closed auction={} winner={} bid={} closedAt={}",
                    closed.id(), closed.highestBidderId(), closed.highestBid(), timestamp);
            }
        });

        if (timers.timerCount() > 0)
        {
            armTick(timestamp);
        }
        metrics.onStateChanged(book.openCount(), timers.timerCount());
    }

    @Override
    public void onTakeSnapshot(final ExclusivePublication snapshotPublication)
    {
        offerToSnapshot(
            snapshotPublication, codec.encodeSnapshotHeader(book.nextAuctionId(), lastTimestamp, buffer));

        for (final AuctionView auction : book.all())
        {
            offerToSnapshot(snapshotPublication, codec.encodeSnapshotAuction(auction, buffer));
        }
    }

    @Override
    public void onRoleChange(final Cluster.Role newRole)
    {
        metrics.onRoleChange(newRole);
    }

    @Override
    public void onTerminate(final Cluster cluster)
    {
    }

    private int encode(final long correlationId, final AuctionBook.Result result)
    {
        return codec.encodeReply(new AuctionReply(correlationId, result.status(), result.auction()), buffer);
    }

    private AuctionBook.Result create(final Command.CreateAuction command, final long timestamp)
    {
        final AuctionBook.Result result = book.create(
            command.sellerId(), command.startPrice(), command.minIncrement(), command.durationMs(), timestamp);

        if (result.status() == ReplyStatus.OK)
        {
            timers.schedule(result.auction().id(), result.auction().endsAt(), timestamp);
            if (!tickArmed)
            {
                armTick(timestamp);
            }
            metrics.onAuctionOpened();
            metrics.onStateChanged(book.openCount(), timers.timerCount());
        }
        return result;
    }

    /**
     * One replicated timer drives the wheel, and it only runs while there are open auctions.
     *
     * @param timestamp of the current log event.
     */
    private void armTick(final long timestamp)
    {
        while (!cluster.scheduleTimer(TICK_TIMER_ID, timestamp + AuctionTimers.RESOLUTION_MS))
        {
            idleStrategy.idle();
        }
        tickArmed = true;
    }

    /**
     * Replies are best effort and never affect state. A client that has gone away or stays back-pressured loses its
     * reply rather than stalling the cluster, and it sees an RPC timeout instead.
     *
     * @param session to reply to.
     * @param length of the reply already encoded in the buffer.
     */
    private void reply(final ClientSession session, final int length)
    {
        idleStrategy.reset();
        for (int attempt = 0; attempt < MAX_REPLY_ATTEMPTS; attempt++)
        {
            final long result = session.offer(buffer, 0, length);
            if (result > 0 || (result != Publication.BACK_PRESSURED && result != Publication.ADMIN_ACTION))
            {
                return;
            }
            idleStrategy.idle();
        }
    }

    private void offerToSnapshot(final ExclusivePublication publication, final int length)
    {
        idleStrategy.reset();
        while (publication.offer(buffer, 0, length) < 0)
        {
            idleStrategy.idle();
        }
    }

    private void loadSnapshot(final Image snapshotImage)
    {
        idleStrategy.reset();
        while (!snapshotImage.isEndOfStream())
        {
            idleStrategy.idle(snapshotImage.poll(this::onSnapshotFragment, 16));
        }

        book.forEachOpen(auction -> timers.schedule(auction.id(), auction.endsAt(), lastTimestamp));

        // The consensus module stores cluster timers in its own snapshot, so the tick is still pending.
        tickArmed = timers.timerCount() > 0;
        metrics.onStateChanged(book.openCount(), timers.timerCount());
    }

    private void onSnapshotFragment(final DirectBuffer message, final int offset, final int length, final Header header)
    {
        final Object record = codec.decodeSnapshotRecord(message, offset, length);
        if (record instanceof Codec.SnapshotHeader snapshotHeader)
        {
            book.restoreNextAuctionId(snapshotHeader.nextAuctionId());
            lastTimestamp = snapshotHeader.lastTimestamp();
        }
        else
        {
            book.restoreAuction((AuctionView)record);
        }
    }
}
