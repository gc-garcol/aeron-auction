package gc.garcol.aeronauction.cluster.node;

import gc.garcol.aeronauction.cluster.domain.AuctionBook;
import gc.garcol.aeronauction.cluster.domain.AuctionTimers;
import gc.garcol.aeronauction.cluster.metrics.NodeMetrics;
import gc.garcol.aeronauction.protocol.codec.Codec;
import gc.garcol.aeronauction.protocol.request.Request;
import gc.garcol.aeronauction.protocol.model.AuctionView;
import gc.garcol.aeronauction.protocol.reply.AuctionPageReply;
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

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The replicated state machine. Everything that changes state happens in a callback and uses only the log's
 * timestamp, so all nodes stay identical. See section 5 of plan.md for the timer rules.
 */
public final class AuctionService implements ClusteredService
{
    static final long TICK_TIMER_ID = 1;

    private static final Logger LOG = LoggerFactory.getLogger(AuctionService.class);

    private final AuctionBook book = new AuctionBook();
    private final AuctionTimers timers = new AuctionTimers();
    private final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer(Codec.MAX_MESSAGE_LENGTH);
    private final Codec codec = new Codec();
    private final NodeMetrics metrics;
    private final ReadChannel readChannel;
    private final int maxReplyAttempts;
    private final String memberHost;

    private Cluster cluster;
    private IdleStrategy idleStrategy;
    private boolean tickArmed;
    private long lastTimestamp;

    AuctionService(
        final NodeMetrics metrics,
        final ReadChannel readChannel,
        final int maxReplyAttempts,
        final String memberHost)
    {
        this.metrics = metrics;
        this.readChannel = readChannel;
        this.maxReplyAttempts = maxReplyAttempts;
        this.memberHost = memberHost;
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

        final Codec.DecodedRequest decoded;
        try
        {
            decoded = codec.decodeRequest(message, offset, length);
        }
        catch (final IllegalArgumentException ex)
        {
            return; // malformed input must never take down the state machine
        }

        final long correlationId = decoded.correlationId();
        final int replyLength = switch (decoded.request())
        {
            case Request.CreateAuction c -> encode(correlationId, create(c, timestamp));
            case Request.PlaceBid c ->
            {
                final AuctionBook.Result bid = book.placeBid(c.auctionId(), c.bidderId(), c.amount(), timestamp);
                metrics.onBid(bid.status());
                yield encode(correlationId, bid);
            }
            case Request.GetAuction c -> encode(correlationId, book.get(c.auctionId(), timestamp));
            case Request.ListAuctions c ->
            {
                final AuctionBook.Page page = book.list(c.page(), c.size(), c.status(), c.order(), timestamp);
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
        if (newRole == Cluster.Role.LEADER)
        {
            final int memberId = cluster.memberId();
            // Resolving the IP can block, so it happens off the service thread.
            Thread.startVirtualThread(() ->
                LOG.info("node {} became leader, host={} ip={}", memberId, memberHost, ipOf(memberHost)));
        }
    }

    private static String ipOf(final String host)
    {
        try
        {
            return InetAddress.getByName(host).getHostAddress();
        }
        catch (final UnknownHostException ex)
        {
            return "unresolved";
        }
    }

    @Override
    public void onTerminate(final Cluster cluster)
    {
    }

    /**
     * Answers reads that came through the read channel instead of the log. This isn't part of the state machine, so it
     * must only read. Aeron can also call it from inside {@code idleStrategy.idle()}, partway through a callback.
     */
    @Override
    public int doBackgroundWork(final long nowNs)
    {
        return readChannel.poll(book, cluster.role() == Cluster.Role.LEADER, cluster.time(), nowNs);
    }

    private int encode(final long correlationId, final AuctionBook.Result result)
    {
        return codec.encodeReply(correlationId, result.status(), result.auction(), buffer);
    }

    private AuctionBook.Result create(final Request.CreateAuction request, final long timestamp)
    {
        final AuctionBook.Result result = book.create(
            request.sellerId(), request.startPrice(), request.minIncrement(), request.durationMs(), timestamp);

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
     * Replies are the best effort and never affect state. A client that has gone away or stays back-pressured loses its
     * reply rather than stalling the cluster, and it sees an RPC timeout instead.
     *
     * @param session to reply to.
     * @param length  of the reply already encoded in the buffer.
     */
    private void reply(final ClientSession session, final int length)
    {
        idleStrategy.reset();
        for (int attempt = 0; attempt < maxReplyAttempts; attempt++)
        {
            final long result = session.offer(buffer, 0, length);
            if ((result != Publication.BACK_PRESSURED && result != Publication.ADMIN_ACTION))
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
        if (record instanceof Codec.SnapshotHeader(long nextAuctionId, long timestamp))
        {
            book.restoreNextAuctionId(nextAuctionId);
            lastTimestamp = timestamp;
        }
        else
        {
            book.restoreAuction((AuctionView)record);
        }
    }
}
