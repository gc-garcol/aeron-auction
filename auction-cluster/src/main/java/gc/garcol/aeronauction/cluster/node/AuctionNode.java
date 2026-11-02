package gc.garcol.aeronauction.cluster.node;

import gc.garcol.aeronauction.cluster.metrics.NodeMetrics;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * One cluster member in one JVM: media driver, archive, consensus module and auction service.
 */
final class AuctionNode implements AutoCloseable
{
    private final NodeMetrics metrics;
    private final ReadChannel readChannel;
    private final ClusteredMediaDriver clusteredMediaDriver;
    private final ClusteredServiceContainer container;
    private AuctionNode(
        final NodeMetrics metrics,
        final ReadChannel readChannel,
        final ClusteredMediaDriver clusteredMediaDriver,
        final ClusteredServiceContainer container)
    {
        this.metrics = metrics;
        this.readChannel = readChannel;
        this.clusteredMediaDriver = clusteredMediaDriver;
        this.container = container;
    }

    static AuctionNode start(final Config config, final NodeMetrics metrics, final Runnable terminationHook)
    {
        final String memberHost = memberHost(config.clusterMembers(), config.nodeId());
        final String archiveEndpoint = memberField(config.clusterMembers(), config.nodeId(), 5);
        final File clusterDir = new File(config.baseDir(), "cluster");
        final String replicationChannel = "aeron:udp?endpoint=" + memberHost + ":0";

        final MediaDriver.Context driverContext = new MediaDriver.Context()
            .aeronDirectoryName(config.aeronDir())
            .threadingMode(ThreadingMode.SHARED)
            .termBufferSparseFile(true)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true);

        final Archive.Context archiveContext = new Archive.Context()
            .aeronDirectoryName(config.aeronDir())
            .archiveDir(new File(config.baseDir(), "archive"))
            .controlChannel("aeron:udp?endpoint=" + archiveEndpoint)
            .replicationChannel(replicationChannel)
            .localControlChannel("aeron:ipc")
            .recordingEventsEnabled(false)
            .threadingMode(ArchiveThreadingMode.SHARED);

        final AeronArchive.Context archiveClient = new AeronArchive.Context()
            .controlRequestChannel("aeron:ipc")
            .controlResponseChannel("aeron:ipc");

        final ConsensusModule.Context consensusContext = new ConsensusModule.Context()
            .clusterMemberId(config.nodeId())
            .clusterMembers(config.clusterMembers())
            .clusterDir(clusterDir)
            .aeronDirectoryName(config.aeronDir())
            .ingressChannel("aeron:udp")
            .logChannel(config.node().logChannel())
            .replicationChannel(replicationChannel)
            .leaderHeartbeatTimeoutNs(TimeUnit.MILLISECONDS.toNanos(config.node().leaderHeartbeatTimeoutMs()))
            .startupCanvassTimeoutNs(TimeUnit.MILLISECONDS.toNanos(config.node().startupCanvassTimeoutMs()))
            .terminationHook(terminationHook)
            .archiveContext(archiveClient.clone());

        ClusteredMediaDriver clusteredMediaDriver = null;
        ReadChannel readChannel = null;
        try
        {
            clusteredMediaDriver = ClusteredMediaDriver.launch(driverContext, archiveContext, consensusContext);

            readChannel = new ReadChannel(
                config.aeronDir(), config.readEndpoint(), config.nodeId(), config.readChannel(), metrics);

            final ClusteredServiceContainer.Context serviceContext = new ClusteredServiceContainer.Context()
                .aeronDirectoryName(config.aeronDir())
                .clusterDir(clusterDir)
                .clusteredService(new AuctionService(metrics, readChannel, config.node().maxReplyAttempts()))
                .terminationHook(terminationHook)
                .archiveContext(archiveClient.clone());

            final ClusteredServiceContainer container = ClusteredServiceContainer.launch(serviceContext);

            metrics.connect(config.aeronDir());
            return new AuctionNode(metrics, readChannel, clusteredMediaDriver, container);
        }
        catch (final RuntimeException ex)
        {
            CloseHelper.quietCloseAll(metrics, readChannel, clusteredMediaDriver);
            throw ex;
        }
    }

    private static String memberHost(final String members, final int nodeId)
    {
        final String endpoint = memberField(members, nodeId, 1);
        return endpoint.substring(0, endpoint.lastIndexOf(':'));
    }

    /**
     * Reads one field of a member. The format is {@code id,ingress,consensus,log,catchup,archive}, with members
     * separated by {@code |}.
     *
     * @param members the full cluster member string.
     * @param nodeId  of the member to read.
     * @param field   index within the member.
     * @return the field's value.
     */
    private static String memberField(final String members, final int nodeId, final int field)
    {
        for (final String member : members.split("\\|"))
        {
            final String[] fields = member.split(",");
            if (Integer.parseInt(fields[0]) == nodeId)
            {
                return fields[field];
            }
        }
        throw new IllegalArgumentException("node " + nodeId + " is not in " + members);
    }

    @Override
    public void close()
    {
        CloseHelper.closeAll(metrics, container, readChannel, clusteredMediaDriver);
    }

    record Config(
        int nodeId,
        String clusterMembers,
        File baseDir,
        String aeronDir,
        String readEndpoint,
        AuctionNodeProperties node,
        ReadChannelProperties readChannel)
    {
    }
}
