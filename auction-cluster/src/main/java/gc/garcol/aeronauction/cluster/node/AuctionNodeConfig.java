package gc.garcol.aeronauction.cluster.node;

import gc.garcol.aeronauction.cluster.metrics.NodeMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts the cluster node with the application, and closes it when the application stops.
 */
@Configuration
@EnableConfigurationProperties({AuctionNodeProperties.class, ReadChannelProperties.class})
public class AuctionNodeConfig
{
    private static final Logger LOG = LoggerFactory.getLogger(AuctionNodeConfig.class);

    /**
     * Stops the application when the cluster terminates, for example after {@code ClusterTool shutdown}. Runs on a
     * new thread, because closing the node from one of its own threads would wait on itself.
     *
     * @param context of the application.
     * @return the hook, which is safe to call more than once.
     */
    private static Runnable exitOnce(final ConfigurableApplicationContext context)
    {
        final AtomicBoolean called = new AtomicBoolean();
        return () ->
        {
            if (called.compareAndSet(false, true))
            {
                new Thread(() -> System.exit(SpringApplication.exit(context)), "cluster-terminated").start();
            }
        };
    }

    @Bean(destroyMethod = "close")
    AuctionNode auctionNode(
        final MeterRegistry registry,
        final ConfigurableApplicationContext context,
        final AuctionNodeProperties nodeProperties,
        final ReadChannelProperties readChannelProperties,
        @Value("${auction.node-id}") final int nodeId,
        @Value("${auction.cluster-members}") final String clusterMembers,
        @Value("${auction.cluster-dir}") final File clusterDir,
        @Value("${auction.aeron-dir}") final String aeronDir,
        @Value("${auction.read-endpoint}") final String readEndpoint)
    {
        final AuctionNode.Config config = new AuctionNode.Config(
            nodeId, clusterMembers, clusterDir, aeronDir, readEndpoint, nodeProperties, readChannelProperties);
        final AuctionNode node = AuctionNode.start(config, new NodeMetrics(registry), exitOnce(context));
        LOG.info("node {} started", nodeId);
        return node;
    }
}
