package gc.garcol.aeronauction.client.rpc;

import gc.garcol.aeronauction.protocol.Codec;
import gc.garcol.aeronauction.protocol.Command;
import gc.garcol.aeronauction.protocol.Reply;
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
    private record Request(Command command, CompletableFuture<Reply> future, long startNanos)
    {
    }

    private record Pending(Request request, long timerId)
    {
    }

    private static final Logger LOG = LoggerFactory.getLogger(ClusterRpcAgent.class);
    private static final int QUEUE_CAPACITY = 4096;
    private static final int MAX_SENDS_PER_CYCLE = 64;
    private static final long KEEP_ALIVE_INTERVAL_MS = 1_000;
    private static final long RECONNECT_DELAY_MS = 500;
    private static final long TICK_RESOLUTION_MS = 16;
    private static final int TICKS_PER_WHEEL = 256;
    private static final long NO_CORRELATION = -1;

    private final AeronCluster.Context clusterContext;
    private final long rpcTimeoutMs;
    private final MeterRegistry registry;
    private final ManyToOneConcurrentArrayQueue<Request> queue = new ManyToOneConcurrentArrayQueue<>(QUEUE_CAPACITY);
    private final Long2ObjectHashMap<Pending> pending = new Long2ObjectHashMap<>();
    private final Long2LongHashMap timerToCorrelation = new Long2LongHashMap(NO_CORRELATION);
    private final DeadlineTimerWheel wheel;
    private final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer(Codec.MAX_MESSAGE_LENGTH);
    private final Codec codec = new Codec();
    private final AtomicInteger pendingGauge = new AtomicInteger();
    private final Counter reconnects;

    private AeronCluster.AsyncConnect asyncConnect;
    private AeronCluster cluster;
    private long nextCorrelationId = 1;
    private long nextKeepAliveMs;
    private long nextConnectAttemptMs;
    private boolean everConnected;
    /**
     * -- GETTER --
     *  Says whether there is a cluster session.
     *
     * @return true if connected.
     */
    @Getter
    private volatile boolean connected;

    /**
     * Creates the agent. It connects once its thread starts.
     *
     * @param clusterContext template for each connection attempt.
     * @param rpcTimeoutMs how long to wait for a reply.
     * @param registry for metrics.
     */
    public ClusterRpcAgent(
        final AeronCluster.Context clusterContext, final long rpcTimeoutMs, final MeterRegistry registry)
    {
        this.clusterContext = clusterContext.egressListener(this);
        this.rpcTimeoutMs = rpcTimeoutMs;
        this.registry = registry;
        this.wheel = new DeadlineTimerWheel(
            TimeUnit.MILLISECONDS, System.currentTimeMillis(), TICK_RESOLUTION_MS, TICKS_PER_WHEEL);
        this.reconnects = registry.counter("auction.client.reconnects");
        registry.gauge("auction.rpc.pending", pendingGauge);
    }

    /**
     * Sends a command to the cluster. Safe to call from any thread.
     *
     * @param command to send.
     * @return a future holding the reply, which fails with {@link RpcFailure} if no reply can be given.
     */
    public CompletableFuture<Reply> submit(final Command command)
    {
        final Request request = new Request(command, new CompletableFuture<>(), System.nanoTime());
        if (!queue.offer(request))
        {
            fail(request, RpcFailure.Kind.UNAVAILABLE, "queue_full", "client request queue is full");
        }
        return request.future();
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
                request -> fail(request, RpcFailure.Kind.UNAVAILABLE, "no_session", "not connected to the cluster"),
                MAX_SENDS_PER_CYCLE);
        }
        else
        {
            work += queue.drain(request -> send(request, nowMs), MAX_SENDS_PER_CYCLE);
            work += cluster.pollEgress();
            if (nowMs >= nextKeepAliveMs && !cluster.isClosed())
            {
                cluster.sendKeepAlive();
                nextKeepAliveMs = nowMs + KEEP_ALIVE_INTERVAL_MS;
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
        final Reply reply = codec.decodeReply(message, offset, length);
        final Pending completed = pending.remove(reply.correlationId());
        if (completed == null)
        {
            return; // already timed out, and the caller was told the outcome is unknown
        }
        wheel.cancelTimer(completed.timerId());
        timerToCorrelation.remove(completed.timerId());
        pendingGauge.set(pending.size());
        record(completed.request(), "ok");
        completed.request().future().complete(reply);
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
        queue.drain(request -> fail(request, RpcFailure.Kind.UNAVAILABLE, "shutdown", "client is shutting down"));
        CloseHelper.quietCloseAll(asyncConnect, cluster);
        connected = false;
    }

    private void send(final Request request, final long nowMs)
    {
        final long correlationId = nextCorrelationId++;
        final int length = codec.encode(request.command(), correlationId, buffer);
        if (cluster.offer(buffer, 0, length) < 0)
        {
            fail(request, RpcFailure.Kind.UNAVAILABLE, "back_pressure", "cluster is unavailable or back-pressured");
            return;
        }
        final long timerId = wheel.scheduleTimer(nowMs + rpcTimeoutMs);
        timerToCorrelation.put(timerId, correlationId);
        pending.put(correlationId, new Pending(request, timerId));
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
            record(timedOut.request(), "timeout");
            timedOut.request().future().completeExceptionally(new RpcFailure(
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
            nextConnectAttemptMs = nowMs + RECONNECT_DELAY_MS;
            return 1;
        }

        if (cluster == null)
        {
            return 0;
        }
        asyncConnect = null;
        connected = true;
        nextKeepAliveMs = nowMs + KEEP_ALIVE_INTERVAL_MS;
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
            fail(entry.request(), RpcFailure.Kind.UNAVAILABLE, "session_lost", "cluster session was lost");
        });
        pending.clear();
        timerToCorrelation.clear();
        pendingGauge.set(0);
    }

    private void fail(final Request request, final RpcFailure.Kind kind, final String reason, final String message)
    {
        registry.counter("auction.rpc.rejected", "reason", reason).increment();
        record(request, "rejected");
        request.future().completeExceptionally(new RpcFailure(kind, message));
    }

    private void record(final Request request, final String outcome)
    {
        Timer.builder("auction.rpc.duration")
            .tag("type", request.command().getClass().getSimpleName())
            .tag("outcome", outcome)
            .publishPercentileHistogram()
            .register(registry)
            .record(System.nanoTime() - request.startNanos(), TimeUnit.NANOSECONDS);
    }
}
