package gc.garcol.aeronauction.client.rpc;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the client's end of the read channel, from {@code auction.read-channel} in application.yaml.
 *
 * @param fragmentLimit       how many reply fragments to handle per poll.
 * @param helloWhileWaitingMs how often to greet a leader that hasn't acknowledged.
 * @param helloWhileReadyMs   how often to greet a leader that has acknowledged.
 * @param readyTimeoutMs      how long an acknowledgement stays valid. Reads go through the log after that.
 * @param requestTermLength   the term length of the request publication.
 */
@ConfigurationProperties("auction.read-channel")
public record ClusterReadChannelProperties(
    int fragmentLimit,
    long helloWhileWaitingMs,
    long helloWhileReadyMs,
    long readyTimeoutMs,
    String requestTermLength)
{
}
