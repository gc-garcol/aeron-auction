package gc.garcol.aeronauction.cluster.node;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the cluster member, from {@code auction.node} in application.yaml.
 *
 * @param logChannel               the replicated log's channel.
 * @param leaderHeartbeatTimeoutMs how long followers wait for the leader before starting an election.
 * @param startupCanvassTimeoutMs  how long a starting node waits for the other members.
 * @param maxReplyAttempts         how many times the service retries a back-pressured reply before dropping it.
 */
@ConfigurationProperties("auction.node")
public record AuctionNodeProperties(
    String logChannel,
    long leaderHeartbeatTimeoutMs,
    long startupCanvassTimeoutMs,
    int maxReplyAttempts)
{
}
