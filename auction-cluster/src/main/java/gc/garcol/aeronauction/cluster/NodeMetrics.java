package gc.garcol.aeronauction.cluster;

import gc.garcol.aeronauction.protocol.AuctionView;
import gc.garcol.aeronauction.protocol.sbe.ReplyStatus;
import io.aeron.Aeron;
import io.aeron.AeronCounters;
import io.aeron.cluster.service.Cluster;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.agrona.CloseHelper;
import org.agrona.concurrent.status.CountersReader;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics for one node: business metrics from the service and Aeron's own counters.
 *
 * <p>A restarted node replays its log, so business counters only increment on the leader, otherwise a restart would
 * count everything twice. Gauges reflect current state on every node.
 */
final class NodeMetrics implements AutoCloseable
{
    private static final Map<Integer, String> CLUSTER_COUNTERS = Map.of(
        AeronCounters.CLUSTER_NODE_ROLE_TYPE_ID, "aeron.cluster.role",
        AeronCounters.CLUSTER_COMMIT_POSITION_TYPE_ID, "aeron.cluster.commit.position",
        AeronCounters.CLUSTER_ELECTION_STATE_TYPE_ID, "aeron.cluster.election.state",
        AeronCounters.CLUSTER_LEADERSHIP_TERM_ID_TYPE_ID, "aeron.cluster.leadership.term",
        AeronCounters.CLUSTER_CONSENSUS_MODULE_ERROR_COUNT_TYPE_ID, "aeron.cluster.errors",
        AeronCounters.CLUSTER_CLIENT_TIMEOUT_COUNT_TYPE_ID, "aeron.cluster.client.timeouts",
        AeronCounters.CLUSTER_CLUSTERED_SERVICE_MAX_CYCLE_TIME_TYPE_ID, "aeron.cluster.service.max.cycle.ns");

    private static final Map<Integer, String> DRIVER_COUNTERS = Map.of(
        AeronCounters.SYSTEM_COUNTER_ID_ERRORS, "aeron.driver.errors",
        AeronCounters.SYSTEM_COUNTER_ID_NAK_MESSAGES_RECEIVED, "aeron.driver.naks.received",
        AeronCounters.SYSTEM_COUNTER_ID_SHORT_SENDS, "aeron.driver.short.sends");

    private static final int ANY_KEY = -1;

    private final MeterRegistry registry;
    private final Counter auctionsOpened;
    private final Counter auctionsClosed;
    private final Map<ReplyStatus, Counter> bids = new EnumMap<>(ReplyStatus.class);
    private final DistributionSummary closeLag;
    private final AtomicLong openAuctions = new AtomicLong();
    private final AtomicLong wheelTimers = new AtomicLong();

    private volatile boolean leader;
    private volatile Aeron aeron;

    NodeMetrics(final MeterRegistry registry)
    {
        this.registry = registry;
        // Not "created": the Prometheus naming convention strips that suffix from counters.
        auctionsOpened = Counter.builder("auction.auctions.opened").register(registry);
        auctionsClosed = Counter.builder("auction.auctions.closed").register(registry);
        for (final ReplyStatus status : ReplyStatus.values())
        {
            if (status == ReplyStatus.NULL_VAL)
            {
                continue;
            }
            bids.put(status, Counter.builder("auction.bids").tag("result", status.name()).register(registry));
        }
        closeLag = DistributionSummary.builder("auction.close.lag.ms")
            .description("Time between an auction's deadline and the tick that closed it")
            .publishPercentileHistogram()
            .register(registry);
        registry.gauge("auction.open.auctions", openAuctions);
        registry.gauge("auction.wheel.timers", wheelTimers);
    }

    /**
     * Connects to Aeron's counters. Separate from the constructor because they need a running media driver.
     *
     * @param aeronDir of the node's media driver.
     */
    void connect(final String aeronDir)
    {
        aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir));
        CLUSTER_COUNTERS.forEach((typeId, name) -> registerGauge(name, typeId, ANY_KEY));
        DRIVER_COUNTERS.forEach((systemCounterId, name) ->
            registerGauge(name, AeronCounters.DRIVER_SYSTEM_COUNTER_TYPE_ID, systemCounterId));
    }

    void onRoleChange(final Cluster.Role role)
    {
        leader = role == Cluster.Role.LEADER;
    }

    void onAuctionOpened()
    {
        if (leader)
        {
            auctionsOpened.increment();
        }
    }

    void onBid(final ReplyStatus status)
    {
        if (leader)
        {
            bids.get(status).increment();
        }
    }

    void onAuctionClosed(final AuctionView auction, final long closedAt)
    {
        if (leader)
        {
            auctionsClosed.increment();
            closeLag.record(closedAt - auction.endsAt());
        }
    }

    void onChannelRead(final String result)
    {
        registry.counter("auction.read.channel.requests", "result", result).increment();
    }

    void onStateChanged(final int open, final long timers)
    {
        openAuctions.set(open);
        wheelTimers.set(timers);
    }

    @Override
    public void close()
    {
        CloseHelper.close(aeron);
    }

    private void registerGauge(final String name, final int typeId, final int key)
    {
        Gauge.builder(name, () -> readCounter(typeId, key)).register(registry);
    }

    /**
     * Reads a counter when metrics are scraped.
     *
     * @param wantedTypeId of the counter.
     * @param wantedKey the first int of the counter's key, or {@link #ANY_KEY}.
     * @return the counter's value, or NaN if it is not available.
     */
    private double readCounter(final int wantedTypeId, final int wantedKey)
    {
        final Aeron client = aeron;
        if (client == null || client.isClosed())
        {
            return Double.NaN;
        }

        final CountersReader counters = client.countersReader();
        final double[] value = {Double.NaN};
        counters.forEach((counterId, typeId, keyBuffer, label) ->
        {
            if (typeId == wantedTypeId && (wantedKey == ANY_KEY || keyBuffer.getInt(0) == wantedKey))
            {
                value[0] = counters.getCounterValue(counterId);
            }
        });
        return value[0];
    }
}
