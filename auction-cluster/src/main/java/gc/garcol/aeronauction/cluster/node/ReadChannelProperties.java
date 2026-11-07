package gc.garcol.aeronauction.cluster.node;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the node's end of the read channel, from {@code auction.read-channel} in application.yaml.
 *
 * @param fragmentLimit    how many request fragments to handle per poll.
 * @param maxClients       how many clients can use the read channel at once.
 * @param maxChannelLength the longest reply channel a client can send.
 * @param replyTermLength  the term length of each reply publication.
 * @param clientTimeoutMs  how long a client can go without a heartbeat before it's dropped.
 * @param sweepIntervalMs  how often to look for clients to drop.
 */
@ConfigurationProperties("auction.read-channel")
public record ReadChannelProperties(
    int fragmentLimit,
    int maxClients,
    int maxChannelLength,
    String replyTermLength,
    long clientTimeoutMs,
    long sweepIntervalMs)
{
}
