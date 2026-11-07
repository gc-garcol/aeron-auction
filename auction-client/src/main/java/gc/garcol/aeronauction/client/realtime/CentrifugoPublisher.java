package gc.garcol.aeronauction.client.realtime;

import gc.garcol.aeronauction.client.realtime.proto.AuctionEvent;
import gc.garcol.aeronauction.protocol.model.AuctionView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes auction events to Centrifugo's HTTP server API, which pushes them to every subscribed browser as binary
 * protobuf frames.
 *
 * <p>Publishing is best effort and runs after the cluster has replied, so it never delays or changes an HTTP
 * response. A lost event only makes the web UI wait for its next poll. Each client pod publishes the writes that it
 * handled, so an event is published once. While live updates are off, it does nothing.
 */
public final class CentrifugoPublisher
{
    private static final Logger LOG = LoggerFactory.getLogger(CentrifugoPublisher.class);

    private final RealtimeProperties properties;
    private final HttpClient http;
    private final URI publishUri;
    private final Counter published;
    private final Counter failed;
    private final AtomicBoolean failing = new AtomicBoolean();

    /**
     * Creates the publisher.
     *
     * @param properties the live update settings.
     * @param registry   for metrics.
     */
    public CentrifugoPublisher(final RealtimeProperties properties, final MeterRegistry registry)
    {
        this.properties = properties;
        this.published = registry.counter("auction.realtime.publish", "result", "ok");
        this.failed = registry.counter("auction.realtime.publish", "result", "failed");
        if (properties.enabled())
        {
            this.publishUri = URI.create(properties.apiUrl().replaceAll("/+$", "") + "/publish");
            this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.publishTimeoutMs()))
                .build();
            LOG.info("live updates go to Centrifugo at {}, channel {}", publishUri, properties.channel());
        }
        else
        {
            this.publishUri = null;
            this.http = null;
            LOG.info("live updates are off, because auction.realtime.api-url is not set");
        }
    }

    /**
     * Publishes an auction's state without waiting for Centrifugo.
     *
     * @param type    what happened.
     * @param auction the auction right after the write.
     */
    public void publish(final AuctionEvent.Type type, final AuctionView auction)
    {
        if (http == null)
        {
            return;
        }
        final String data = Base64.getEncoder().encodeToString(toEvent(type, auction).toByteArray());
        final String body = "{\"channel\":\"" + properties.channel() + "\",\"b64data\":\"" + data + "\"}";
        final HttpRequest request = HttpRequest.newBuilder(publishUri)
            .timeout(Duration.ofMillis(properties.publishTimeoutMs()))
            .header("Content-Type", "application/json")
            .header("X-API-Key", properties.apiKey())
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) ->
        {
            if (error != null)
            {
                onFailure(error.toString());
            }
            else if (response.statusCode() != 200 || response.body().contains("\"error\""))
            {
                // Centrifugo answers an API error with 200 and an error object in the body.
                onFailure("HTTP " + response.statusCode() + " " + response.body());
            }
            else
            {
                published.increment();
                if (failing.compareAndSet(true, false))
                {
                    LOG.info("publishing to Centrifugo works again");
                }
            }
        });
    }

    static AuctionEvent toEvent(final AuctionEvent.Type type, final AuctionView auction)
    {
        final AuctionEvent.Builder event = AuctionEvent.newBuilder()
            .setType(type)
            .setId(auction.id())
            .setSellerId(auction.sellerId())
            .setStartPrice(auction.startPrice())
            .setMinIncrement(auction.minIncrement())
            .setEndsAt(auction.endsAt());
        if (auction.highestBidderId() != AuctionView.NO_BIDDER)
        {
            event.setHighestBid(auction.highestBid()).setHighestBidderId(auction.highestBidderId());
        }
        return event.build();
    }

    // Logs only when publishing starts failing, so an unreachable Centrifugo doesn't flood the log.
    private void onFailure(final String reason)
    {
        failed.increment();
        if (failing.compareAndSet(false, true))
        {
            LOG.warn("publishing to Centrifugo at {} failed, the web UI falls back to polling: {}", publishUri, reason);
        }
    }
}
