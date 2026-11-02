package gc.garcol.aeronauction.client.rpc;

import io.aeron.cluster.client.AeronCluster;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.micrometer.core.instrument.MeterRegistry;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the media driver, the RPC agent and its thread, and the health check.
 */
@Configuration
@EnableConfigurationProperties({ClusterRpcProperties.class, ClusterReadChannelProperties.class})
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
        final ClusterRpcProperties properties,
        final ClusterReadChannelProperties readChannelProperties,
        @Value("${auction.ingress-endpoints}") final String ingressEndpoints,
        @Value("${auction.egress-host}") final String egressHost,
        @Value("${auction.rpc-timeout-ms}") final long rpcTimeoutMs,
        @Value("${auction.read-endpoints}") final String readEndpoints,
        @Value("${auction.read-reply-endpoint}") final String readReplyEndpoint,
        @Value("${auction.reads-through-log}") final boolean readsThroughLog)
    {
        final AeronCluster.Context context = new AeronCluster.Context()
            .aeronDirectoryName(mediaDriver.aeronDirectoryName())
            .ingressChannel("aeron:udp")
            .ingressEndpoints(ingressEndpoints)
            .egressChannel("aeron:udp?endpoint=" + egressHost + ":0")
            .messageTimeoutNs(properties.messageTimeoutNs());

        final ClusterRpcAgent agent = new ClusterRpcAgent(context, rpcTimeoutMs, properties, registry);
        if (!readsThroughLog)
        {
            agent.useReadChannel(new ClusterReadChannel(
                mediaDriver.aeronDirectoryName(),
                readEndpoints,
                readReplyEndpoint,
                readChannelProperties,
                agent::onReadReply));
        }
        return agent;
    }

    @Bean(destroyMethod = "close")
    AgentRunner clusterRpcRunner(final ClusterRpcAgent agent, final ClusterRpcProperties properties)
    {
        final ClusterRpcProperties.Idle idle = properties.idle();
        final AgentRunner runner = new AgentRunner(
            new BackoffIdleStrategy(idle.maxSpins(), idle.maxYields(), idle.minParkNs(), idle.maxParkNs()),
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
