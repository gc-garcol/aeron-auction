package gc.garcol.aeronauction.client.rpc;

import gc.garcol.aeronauction.protocol.codec.Codec;
import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.FragmentAssembler;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * The client's end of the read channel, which sends reads directly to the leader so they add nothing to the log.
 * Used only from the RPC agent's thread.
 *
 * <p>It sends heartbeats to the leader, which acknowledges each one. {@link #offer} refuses a read unless the leader has
 * acknowledged recently, and the agent then sends that read through the log instead, such as during a failover.
 */
final class ClusterReadChannel implements AutoCloseable
{
    private static final Logger LOG = LoggerFactory.getLogger(ClusterReadChannel.class);
    private static final int NOT_ACKNOWLEDGED = -1;

    private final ClusterReadChannelProperties properties;
    private final Aeron aeron;
    private final Subscription subscription;
    private final Map<Integer, String> nodeEndpoints;
    private final Map<Integer, Long> registrations = new HashMap<>();
    private final Map<Integer, ExclusivePublication> publications = new HashMap<>();
    private final String replyChannel;
    private final FragmentAssembler assembler;
    private final FragmentHandler replyHandler;
    private final Codec codec = new Codec();
    private final ExpandableArrayBuffer heartbeatBuffer = new ExpandableArrayBuffer(Codec.MAX_MESSAGE_LENGTH);

    private int leaderId = NOT_ACKNOWLEDGED;
    private int acknowledgedBy = NOT_ACKNOWLEDGED;
    private long lastAcknowledgedMs;
    private long nextHeartbeatMs;
    private long nowMs;

    ClusterReadChannel(
        final String aeronDir,
        final String nodeEndpoints,
        final String replyEndpoint,
        final ClusterReadChannelProperties properties,
        final FragmentHandler replyHandler)
    {
        this.properties = properties;
        this.nodeEndpoints = parseEndpoints(nodeEndpoints);
        this.replyHandler = replyHandler;
        this.replyChannel = "aeron:udp?endpoint=" + replyEndpoint;
        this.assembler = new FragmentAssembler(this::onFragment);
        this.aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir));
        this.subscription = aeron.addSubscription(replyChannel, Codec.READ_REPLY_STREAM_ID);
    }

    private static Map<Integer, String> parseEndpoints(final String endpoints)
    {
        final Map<Integer, String> parsed = new HashMap<>();
        for (final String entry : endpoints.split(","))
        {
            final String[] parts = entry.split("=", 2);
            parsed.put(Integer.parseInt(parts[0].trim()), parts[1].trim());
        }
        return parsed;
    }

    /**
     * Receives replies and keeps the handshake with the leader going.
     *
     * @param now             the current time in milliseconds.
     * @param currentLeaderId from the cluster session.
     * @return the amount of work done.
     */
    int doWork(final long now, final int currentLeaderId)
    {
        nowMs = now;
        if (currentLeaderId != leaderId)
        {
            leaderId = currentLeaderId;
            nextHeartbeatMs = 0; // send a heartbeat to a new leader straight away
        }

        final int work = subscription.poll(assembler, properties.fragmentLimit());
        if (now >= nextHeartbeatMs)
        {
            sendHeartbeat();
            final long intervalMs =
                isReady() ? properties.heartbeatWhileReadyMs() : properties.heartbeatWhileWaitingMs();
            nextHeartbeatMs = now + intervalMs;
        }
        return work;
    }

    /**
     * Sends a read to the leader, if it can.
     *
     * @param buffer holding the encoded read.
     * @param length of the read.
     * @return false if the leader isn't ready, or the read couldn't be sent. Nothing was sent in that case.
     */
    boolean offer(final DirectBuffer buffer, final int length)
    {
        final ExclusivePublication publication = isReady() ? publication(leaderId) : null;
        return publication != null && publication.offer(buffer, 0, length) > 0;
    }

    @Override
    public void close()
    {
        CloseHelper.close(aeron);
    }

    private boolean isReady()
    {
        return acknowledgedBy == leaderId && nowMs - lastAcknowledgedMs < properties.readyTimeoutMs();
    }

    private void sendHeartbeat()
    {
        final ExclusivePublication publication = publication(leaderId);
        if (publication != null)
        {
            publication.offer(heartbeatBuffer, 0, codec.encodeReadHeartbeat(replyChannel, heartbeatBuffer));
        }
    }

    private ExclusivePublication publication(final int nodeId)
    {
        final ExclusivePublication existing = publications.get(nodeId);
        if (existing != null)
        {
            return existing;
        }

        final String endpoint = nodeEndpoints.get(nodeId);
        if (endpoint == null)
        {
            return null; // no leader known yet, or the leader isn't configured here
        }

        final String channel =
            "aeron:udp?endpoint=" + endpoint + "|term-length=" + properties.requestTermLength() + "|sparse=true";
        final long registrationId = registrations.computeIfAbsent(nodeId, ignored ->
            aeron.asyncAddExclusivePublication(channel, Codec.READ_REQUEST_STREAM_ID));
        try
        {
            final ExclusivePublication publication = aeron.getExclusivePublication(registrationId);
            if (publication != null)
            {
                publications.put(nodeId, publication);
            }
            return publication;
        }
        catch (final RuntimeException ex)
        {
            LOG.warn("read channel to node {} failed, retrying: {}", nodeId, ex.getMessage());
            registrations.remove(nodeId); // such as a stopped node's hostname not resolving yet
            return null;
        }
    }

    private void onFragment(final DirectBuffer message, final int offset, final int length, final Header header)
    {
        if (codec.isReadReady(message, offset, length))
        {
            acknowledgedBy = codec.decodeReadReady(message, offset, length);
            lastAcknowledgedMs = nowMs;
        }
        else
        {
            replyHandler.onFragment(message, offset, length, header);
        }
    }
}
