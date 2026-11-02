package gc.garcol.aeronauction.client.rpc;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the RPC agent, from {@code auction.rpc} in application.yaml.
 *
 * @param queueCapacity         how many requests can wait for the agent. Must be a power of 2 or is rounded up to one.
 * @param maxSendsPerCycle      how many requests the agent sends in one duty cycle.
 * @param keepAliveIntervalMs   how often to send a session keep-alive.
 * @param reconnectDelayMs      how long to wait after a failed connection attempt.
 * @param timerTickResolutionMs the tick of the RPC timeout wheel. Must be a power of 2.
 * @param timerTicksPerWheel    the size of the RPC timeout wheel. Must be a power of 2.
 * @param noCorrelation         marks a missing correlation ID. Must be below 1, because real IDs start at 1.
 * @param messageTimeoutNs      how long the cluster client waits for messages while connecting and offering.
 * @param idle                  how the agent's thread waits when it has no work.
 */
@ConfigurationProperties("auction.rpc")
public record ClusterRpcProperties(
    int queueCapacity,
    int maxSendsPerCycle,
    long keepAliveIntervalMs,
    long reconnectDelayMs,
    long timerTickResolutionMs,
    int timerTicksPerWheel,
    long noCorrelation,
    long messageTimeoutNs,
    Idle idle)
{
    /**
     * Settings of the agent thread's back-off.
     *
     * @param maxSpins  spins before yielding.
     * @param maxYields yields before parking.
     * @param minParkNs the first park period.
     * @param maxParkNs the longest park period.
     */
    public record Idle(long maxSpins, long maxYields, long minParkNs, long maxParkNs)
    {
    }
}
