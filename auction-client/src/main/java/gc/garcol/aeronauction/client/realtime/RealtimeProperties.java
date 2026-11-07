package gc.garcol.aeronauction.client.realtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.regex.Pattern;

/**
 * Settings of the live updates, from {@code auction.realtime} in application.yaml. Live updates are off when
 * {@code apiUrl} is blank, and the web UI then only polls.
 *
 * @param apiUrl           Centrifugo's server API, such as {@code http://centrifugo:8000/api}.
 * @param apiKey           Centrifugo's {@code http_api.key}.
 * @param websocketUrl     where browsers connect, absolute or relative to the page, such as
 *                         {@code ws://localhost:8000/connection/websocket}.
 * @param channel          the channel that every auction event goes to.
 * @param publishTimeoutMs how long one publish may take before it counts as failed.
 */
@ConfigurationProperties("auction.realtime")
public record RealtimeProperties(
    String apiUrl,
    String apiKey,
    String websocketUrl,
    String channel,
    long publishTimeoutMs)
{
    // The channel goes into a JSON body unescaped, so it may only hold characters that need no escaping.
    private static final Pattern CHANNEL = Pattern.compile("[A-Za-z0-9_:\\-.]+");

    /**
     * Checks the settings.
     *
     * @throws IllegalArgumentException if live updates are on and a setting is missing or invalid.
     */
    public RealtimeProperties
    {
        if (isNotBlank(apiUrl))
        {
            if (!isNotBlank(apiKey))
            {
                throw new IllegalArgumentException("auction.realtime.api-key is required when api-url is set");
            }
            if (!isNotBlank(websocketUrl))
            {
                throw new IllegalArgumentException("auction.realtime.websocket-url is required when api-url is set");
            }
            if (channel == null || !CHANNEL.matcher(channel).matches())
            {
                throw new IllegalArgumentException("auction.realtime.channel must match " + CHANNEL);
            }
        }
    }

    /**
     * Says whether live updates are on.
     *
     * @return true if events are published to Centrifugo.
     */
    public boolean enabled()
    {
        return isNotBlank(apiUrl);
    }

    private static boolean isNotBlank(final String value)
    {
        return value != null && !value.isBlank();
    }
}
