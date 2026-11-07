package gc.garcol.aeronauction.client.rpc;

import gc.garcol.aeronauction.protocol.codec.Codec;
import gc.garcol.aeronauction.protocol.request.Request;
import gc.garcol.aeronauction.protocol.reply.Reply;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.logbuffer.Header;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import org.agrona.CloseHelper;
import org.agrona.DeadlineTimerWheel;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the cluster session. {@link AeronCluster} is not thread-safe, so HTTP threads only enqueue requests, and this
 * agent's single thread does everything else: sending, receiving replies, RPC timeouts and reconnecting.
 *
 * <p>Timed-out commands are never retried, because the original may still commit and a retry could bid twice.
 */
public final class ClusterRpcAgent implements Agent, EgressListener
{
    private static final Logger LOG = LoggerFactory.getLogger(ClusterRpcAgent.class);
    private final AeronCluster.Context clusterContext;
    private final long rpcTimeoutMs;
    private final ClusterRpcProperties properties;
    private final MeterRegistry registry;
    private final ManyToOneConcurrentArrayQueue<Submission> queue;
    private final Long2ObjectHashMap<Pending> pending = new Long2ObjectHashMap<>();
    private final Long2LongHashMap timerToCorrelation;
    private final DeadlineTimerWheel wheel;
    private final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer(Codec.MAX_MESSAGE_LENGTH);
    private final Codec codec = new Codec();
    private final AtomicInteger pendingGauge = new AtomicInteger();
    private final Counter reconnects;
    private ClusterReadChannel readChannel;
    private AeronCluster.AsyncConnect asyncConnect;
    private AeronCluster cluster;
    private long nextCorrelationId = 1;
    private long nextKeepAliveMs;
    private long nextConnectAttemptMs;
    private boolean everConnected;
    /**
     * -- GETTER --
     * Says whether there is a cluster session.
     *
     * @return true if connected.
     */
    @Getter
    private volatile boolean connected;
    /**
     * Creates the agent. It connects once its thread starts.
     *
     * @param clusterContext template for each connection attempt.
     * @param rpcTimeoutMs   how long to wait for a reply.
     * @param properties     the agent's settings.
     * @param registry       for metrics.
     */
    public ClusterRpcAgent(
        final AeronCluster.Context clusterContext,
        final long rpcTimeoutMs,
        final ClusterRpcProperties properties,
        final MeterRegistry registry)
    {
        if (properties.noCorrelation() >= 1)
        {
            throw new IllegalArgumentException("auction.rpc.no-correlation must be below 1, as real IDs start at 1");
        }
        this.clusterContext = clusterContext.egressListener(this);
        this.rpcTimeoutMs = rpcTimeoutMs;
        this.properties = properties;
        this.queue = new ManyToOneConcurrentArrayQueue<>(properties.queueCapacity());
        this.timerToCorrelation = new Long2LongHashMap(properties.noCorrelation());
        this.registry = registry;
        this.wheel = new DeadlineTimerWheel(
            TimeUnit.MILLISECONDS,
            System.currentTimeMillis(),
            properties.timerTickResolutionMs(),
            properties.timerTicksPerWheel());
        this.reconnects = registry.counter("auction.client.reconnects");
        registry.gauge("auction.rpc.pending", pendingGauge);
    }

    /**
     * Sends reads over a read channel when it's ready, instead of through the log. Call this before the agent starts.
     *
     * @param readChannel to use, which this agent then owns.
     */
    void useReadChannel(final ClusterReadChannel readChannel)
    {
        this.readChannel = readChannel;
    }

    /**
     * Sends a request to the cluster. Safe to call from any thread.
     *
     * @param request to send.
     * @return a future holding the reply, which fails with {@link RpcFailure} if no reply can be given.
     */
    public CompletableFuture<Reply> submit(final Request request)
    {
        final Submission submission = new Submission(request, new CompletableFuture<>(), System.nanoTime());
        if (!queue.offer(submission))
        {
            fail(submission, RpcFailure.Kind.UNAVAILABLE, "queue_full", "client request queue is full");
        }
        return submission.future();
    }

    @Override
    public int doWork()
    {
        final long nowMs = System.currentTimeMillis();
        int work = expireTimeouts(nowMs);

        if (cluster != null && cluster.isClosed())
        {
            onDisconnected();
        }

        if (cluster == null)
        {
            work += tryConnect(nowMs);
            work += queue.drain(
                submission -> fail(
                    submission, RpcFailure.Kind.UNAVAILABLE, "no_session", "not connected to the cluster"),
                properties.maxSendsPerCycle());
        }
        else
        {
            if (readChannel != null)
            {
                work += readChannel.doWork(nowMs, cluster.leaderMemberId());
            }
            work += queue.drain(submission -> send(submission, nowMs), properties.maxSendsPerCycle());
            work += cluster.pollEgress();
            if (nowMs >= nextKeepAliveMs && !cluster.isClosed())
            {
                cluster.sendKeepAlive();
                nextKeepAliveMs = nowMs + properties.keepAliveIntervalMs();
            }
        }
        return work;
    }

    @Override
    public void onMessage(
        final long clusterSessionId,
        final long timestamp,
        final DirectBuffer message,
        final int offset,
        final int length,
        final Header header)
    {
        onReply(message, offset, length);
    }

    /**
     * Handles a reply that came through the read channel.
     *
     * @param message holding the reply.
     * @param offset  of the reply.
     * @param length  of the reply.
     * @param header  of the fragment.
     */
    void onReadReply(final DirectBuffer message, final int offset, final int length, final Header header)
    {
        onReply(message, offset, length);
    }

    private void onReply(final DirectBuffer message, final int offset, final int length)
    {
        final Reply reply = codec.decodeReply(message, offset, length);
        final Pending completed = pending.remove(reply.correlationId());
        if (completed == null)
        {
            return; // already timed out, and the caller was told the outcome is unknown
        }
        wheel.cancelTimer(completed.timerId());
        timerToCorrelation.remove(completed.timerId());
        pendingGauge.set(pending.size());
        record(completed.submission(), "ok");
        completed.submission().future().complete(reply);
    }

    @Override
    public String roleName()
    {
        return "cluster-rpc";
    }

    @Override
    public void onClose()
    {
        failAllPending();
        queue.drain(submission -> fail(submission, RpcFailure.Kind.UNAVAILABLE, "shutdown", "client is shutting down"));
        CloseHelper.quietCloseAll(asyncConnect, cluster, readChannel);
        connected = false;
    }

    private void send(final Submission submission, final long nowMs)
    {
        final long correlationId = nextCorrelationId++;
        final int length = codec.encode(submission.request(), correlationId, buffer);
        final boolean isRead = submission.request() instanceof Request.GetAuction ||
            submission.request() instanceof Request.ListAuctions;
        final boolean sentOnReadChannel = isRead && readChannel != null && readChannel.offer(buffer, length);
        if (isRead)
        {
            registry.counter("auction.rpc.reads", "route", sentOnReadChannel ? "read_channel" : "log").increment();
        }
        if (!sentOnReadChannel && cluster.offer(buffer, 0, length) < 0)
        {
            fail(submission, RpcFailure.Kind.UNAVAILABLE, "back_pressure", "cluster is unavailable or back-pressured");
            return;
        }
        final long timerId = wheel.scheduleTimer(nowMs + rpcTimeoutMs);
        timerToCorrelation.put(timerId, correlationId);
        pending.put(correlationId, new Pending(submission, timerId));
        pendingGauge.set(pending.size());
    }

    private int expireTimeouts(final long nowMs)
    {
        int expired = 0;
        while (wheel.currentTickTime() <= nowMs)
        {
            expired += wheel.poll(nowMs, this::onRpcTimeout, Integer.MAX_VALUE);
        }
        return expired + wheel.poll(nowMs, this::onRpcTimeout, Integer.MAX_VALUE);
    }

    private boolean onRpcTimeout(final TimeUnit timeUnit, final long nowMs, final long timerId)
    {
        final Pending timedOut = pending.remove(timerToCorrelation.remove(timerId));
        if (timedOut != null)
        {
            pendingGauge.set(pending.size());
            registry.counter("auction.rpc.timeouts").increment();
            record(timedOut.submission(), "timeout");
            timedOut.submission().future().completeExceptionally(new RpcFailure(
                RpcFailure.Kind.TIMEOUT, "no reply within " + rpcTimeoutMs + "ms; the command may still have applied"));
        }
        return true;
    }

    private int tryConnect(final long nowMs)
    {
        if (asyncConnect == null)
        {
            if (nowMs < nextConnectAttemptMs)
            {
                return 0;
            }
            asyncConnect = AeronCluster.asyncConnect(clusterContext.clone());
        }

        try
        {
            cluster = asyncConnect.poll();
        }
        catch (final Exception ex)
        {
            LOG.warn("cluster connect attempt failed: {}", ex.getMessage());
            CloseHelper.quietClose(asyncConnect);
            asyncConnect = null;
            nextConnectAttemptMs = nowMs + properties.reconnectDelayMs();
            return 1;
        }

        if (cluster == null)
        {
            return 0;
        }
        asyncConnect = null;
        connected = true;
        nextKeepAliveMs = nowMs + properties.keepAliveIntervalMs();
        if (everConnected)
        {
            reconnects.increment();
        }
        everConnected = true;
        LOG.info("connected to cluster, session {}", cluster.clusterSessionId());
        return 1;
    }

    private void onDisconnected()
    {
        LOG.warn("cluster session closed");
        CloseHelper.quietClose(cluster);
        cluster = null;
        connected = false;
        failAllPending();
    }

    private void failAllPending()
    {
        pending.forEach((correlationId, entry) ->
        {
            wheel.cancelTimer(entry.timerId());
            fail(entry.submission(), RpcFailure.Kind.UNAVAILABLE, "session_lost", "cluster session was lost");
        });
        pending.clear();
        timerToCorrelation.clear();
        pendingGauge.set(0);
    }

    private void fail(
        final Submission submission, final RpcFailure.Kind kind, final String reason, final String message)
    {
        registry.counter("auction.rpc.rejected", "reason", reason).increment();
        record(submission, "rejected");
        submission.future().completeExceptionally(new RpcFailure(kind, message));
    }

    private void record(final Submission submission, final String outcome)
    {
        Timer.builder("auction.rpc.duration")
            .tag("type", submission.request().getClass().getSimpleName())
            .tag("outcome", outcome)
            .publishPercentileHistogram()
            .register(registry)
            .record(System.nanoTime() - submission.startNanos(), TimeUnit.NANOSECONDS);
    }

    private record Submission(Request request, CompletableFuture<Reply> future, long startNanos)
    {
    }

    private record Pending(Submission submission, long timerId)
    {
    }
}
