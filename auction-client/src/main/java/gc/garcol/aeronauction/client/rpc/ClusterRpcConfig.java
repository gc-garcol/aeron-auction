package gc.garcol.aeronauction.client.rpc;

import io.aeron.cluster.client.AeronCluster;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.micrometer.core.instrument.MeterRegistry;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Wires the media driver, the RPC agent and its thread, and the health check.
 */
@Configuration
public class ClusterRpcConfig
{
    @Bean(destroyMethod = "close")
    MediaDriver mediaDriver(@Value("${auction.aeron-dir}") final String aeronDir)
    {
        return MediaDriver.launchEmbedded(new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .threadingMode(ThreadingMode.SHARED)
            .termBufferSparseFile(true)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true));
    }

    @Bean
    ClusterRpcAgent clusterRpcAgent(
        final MediaDriver mediaDriver,
        final MeterRegistry registry,
        @Value("${auction.ingress-endpoints}") final String ingressEndpoints,
        @Value("${auction.egress-host}") final String egressHost,
        @Value("${auction.rpc-timeout-ms}") final long rpcTimeoutMs)
    {
        final AeronCluster.Context context = new AeronCluster.Context()
            .aeronDirectoryName(mediaDriver.aeronDirectoryName())
            .ingressChannel("aeron:udp")
            .ingressEndpoints(ingressEndpoints)
            .egressChannel("aeron:udp?endpoint=" + egressHost + ":0")
            .messageTimeoutNs(TimeUnit.SECONDS.toNanos(5));

        return new ClusterRpcAgent(context, rpcTimeoutMs, registry);
    }

    @Bean(destroyMethod = "close")
    AgentRunner clusterRpcRunner(final ClusterRpcAgent agent)
    {
        final AgentRunner runner = new AgentRunner(
            new BackoffIdleStrategy(100, 10, TimeUnit.MICROSECONDS.toNanos(1), TimeUnit.MILLISECONDS.toNanos(1)),
            error -> LoggerFactory.getLogger(ClusterRpcAgent.class).error("agent error", error),
            null,
            agent);
        AgentRunner.startOnThread(runner);
        return runner;
    }

    @Bean
    HealthIndicator clusterHealthIndicator(final ClusterRpcAgent agent)
    {
        return () -> agent.isConnected() ?
            Health.up().build() :
            Health.down().withDetail("cluster", "no session").build();
    }
}
