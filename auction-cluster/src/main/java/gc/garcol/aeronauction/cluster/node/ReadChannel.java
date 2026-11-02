package gc.garcol.aeronauction.cluster.node;

import gc.garcol.aeronauction.cluster.domain.AuctionBook;
import gc.garcol.aeronauction.cluster.metrics.NodeMetrics;
import gc.garcol.aeronauction.protocol.codec.Codec;
import gc.garcol.aeronauction.protocol.command.Command;
import gc.garcol.aeronauction.protocol.reply.AuctionPageReply;
import gc.garcol.aeronauction.protocol.reply.AuctionReply;
import io.aeron.Aeron;
import io.aeron.ChannelUri;
import io.aeron.ExclusivePublication;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.collections.Int2ObjectHashMap;

import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * Answers reads over a separate Aeron stream, so they add nothing to the replicated log.
 *
 * <p>Polled from {@code ClusteredService.doBackgroundWork}, on the service thread, so it reads {@link AuctionBook}
 * without locks. That method can also run from inside the service's retry loops, partway through a callback, which is
 * why this class has its own {@link Codec} and buffer. It must stay read-only and must never block or retry.
 *
 * <p>Only the leader answers, so a replaying or lagging follower never serves its partial state. Answers show committed
 * state and can be briefly stale, because a deposed leader answers until it notices. Bids still go through the log.
 */
final class ReadChannel implements AutoCloseable
{
    private final ReadChannelProperties properties;
    private final long clientTimeoutNs;
    private final long sweepIntervalNs;
    private final Aeron aeron;
    private final Subscription subscription;
    private final int nodeId;
    private final NodeMetrics metrics;
    private final Codec codec = new Codec();
    private final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer(Codec.MAX_MESSAGE_LENGTH);
    private final Int2ObjectHashMap<ClientLink> clients = new Int2ObjectHashMap<>();
    private AuctionBook book;
    private boolean leader;
    private long timestamp;
    private long nowNs;
    private final FragmentHandler fragmentHandler = this::onFragment;
    private long nextSweepNs;
    ReadChannel(
        final String aeronDir,
        final String endpoint,
        final int nodeId,
        final ReadChannelProperties properties,
        final NodeMetrics metrics)
    {
        this.properties = properties;
        this.clientTimeoutNs = TimeUnit.MILLISECONDS.toNanos(properties.clientTimeoutMs());
        this.sweepIntervalNs = TimeUnit.MILLISECONDS.toNanos(properties.sweepIntervalMs());
        this.nodeId = nodeId;
        this.metrics = metrics;
        this.aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir));
        this.subscription = aeron.addSubscription("aeron:udp?endpoint=" + endpoint, Codec.READ_REQUEST_STREAM_ID);
    }

    /**
     * Handles waiting requests.
     *
     * @param book        to read from.
     * @param isLeader    true if this node is the leader. Requests are still consumed otherwise, but not answered.
     * @param clusterTime the timestamp of the last log event, used to show an auction past its deadline as closed.
     * @param nowNs       from {@code doBackgroundWork}.
     * @return the amount of work done.
     */
    int poll(final AuctionBook book, final boolean isLeader, final long clusterTime, final long nowNs)
    {
        this.book = book;
        this.leader = isLeader;
        this.timestamp = clusterTime;
        this.nowNs = nowNs;

        final int work = subscription.poll(fragmentHandler, properties.fragmentLimit());
        if (nowNs >= nextSweepNs)
        {
            nextSweepNs = nowNs + sweepIntervalNs;
            closeStaleClients();
        }
        return work;
    }

    @Override
    public void close()
    {
        clients.values().forEach(link -> CloseHelper.close(link.publication));
        CloseHelper.close(aeron);
    }

    private void onFragment(final DirectBuffer message, final int offset, final int length, final Header header)
    {
        try
        {
            if (codec.isReadHello(message, offset, length))
            {
                onHello(codec.decodeReadHello(message, offset, length), header.sessionId());
            }
            else
            {
                onRead(codec.decodeRequest(message, offset, length), header.sessionId());
            }
        }
        catch (final RuntimeException ex)
        {
            metrics.onChannelRead("malformed"); // anyone can reach this port, and it must not stop the service thread
        }
    }

    private void onHello(final String replyChannel, final int sessionId)
    {
        ClientLink link = clients.get(sessionId);
        if (link == null)
        {
            if (clients.size() >= properties.maxClients() || replyChannel.length() > properties.maxChannelLength())
            {
                metrics.onChannelRead("rejected");
                return;
            }
            final ChannelUri uri = ChannelUri.parse(replyChannel);
            if (!"udp".equals(uri.media()) || uri.get("endpoint") == null)
            {
                metrics.onChannelRead("rejected");
                return;
            }
            uri.put("term-length", properties.replyTermLength());
            uri.put("sparse", "true");
            final long registrationId = aeron.asyncAddExclusivePublication(uri.toString(), Codec.READ_REPLY_STREAM_ID);
            link = new ClientLink(registrationId, nowNs);
            clients.put(sessionId, link);
        }
        link.lastHelloNs = nowNs;

        // Only the leader acknowledges, so a client only starts sending reads here while this node leads.
        final ExclusivePublication publication = resolve(sessionId, link);
        if (leader && publication != null && publication.isConnected())
        {
            publication.offer(buffer, 0, codec.encodeReadReady(nodeId, buffer));
        }
    }

    private void onRead(final Codec.Request request, final int sessionId)
    {
        final long correlationId = request.correlationId();
        final int length;
        switch (request.command())
        {
            case Command.GetAuction c ->
            {
                final AuctionBook.Result result = book.get(c.auctionId(), timestamp);
                length = codec.encodeReply(new AuctionReply(correlationId, result.status(), result.auction()), buffer);
            }
            case Command.ListAuctions c ->
            {
                final AuctionBook.Page page = book.list(c.page(), c.size(), c.status(), c.order(), timestamp);
                length = codec.encodePageReply(
                    new AuctionPageReply(
                        correlationId, page.status(), c.page(), c.size(), page.totalItems(), page.items()),
                    buffer);
            }
            default ->
            {
                metrics.onChannelRead("rejected"); // writes must go through the log, so they're never handled here
                return;
            }
        }

        final ClientLink link = clients.get(sessionId);
        final ExclusivePublication publication = link == null ? null : resolve(sessionId, link);
        if (!leader || publication == null)
        {
            metrics.onChannelRead("not_answered");
        }
        else if (publication.offer(buffer, 0, length) > 0)
        {
            metrics.onChannelRead("answered");
        }
        else
        {
            metrics.onChannelRead("dropped"); // never retry on this thread, and the client times out instead
        }
    }

    private ExclusivePublication resolve(final int sessionId, final ClientLink link)
    {
        if (link.publication == null)
        {
            try
            {
                link.publication = aeron.getExclusivePublication(link.registrationId);
            }
            catch (final RuntimeException ex)
            {
                clients.remove(sessionId); // such as a reply channel that doesn't resolve
                metrics.onChannelRead("rejected");
            }
        }
        return link.publication;
    }

    private void closeStaleClients()
    {
        final Iterator<ClientLink> links = clients.values().iterator();
        while (links.hasNext())
        {
            final ClientLink link = links.next();
            if (nowNs - link.lastHelloNs > clientTimeoutNs)
            {
                links.remove();
                if (link.publication != null)
                {
                    CloseHelper.close(link.publication);
                }
                else
                {
                    aeron.asyncRemovePublication(link.registrationId);
                }
            }
        }
    }

    private static final class ClientLink
    {
        final long registrationId;
        ExclusivePublication publication;
        long lastHelloNs;

        ClientLink(final long registrationId, final long nowNs)
        {
            this.registrationId = registrationId;
            this.lastHelloNs = nowNs;
        }
    }
}
