package gc.garcol.aeronauction.client.realtime;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the web UI where to receive live updates.
 */
@RestController
@RequestMapping("/realtime")
@RequiredArgsConstructor
class RealtimeController
{
    private final RealtimeProperties properties;

    /**
     * Where to connect for live updates.
     *
     * @param websocketUrl Centrifugo's WebSocket endpoint, absolute or relative to the page.
     * @param channel      the channel to subscribe to.
     */
    record RealtimeResponse(String websocketUrl, String channel)
    {
    }

    /**
     * Returns the live update settings.
     *
     * @return the settings, or no content if live updates are off.
     */
    @Operation(summary = "Where to receive live updates over Centrifugo")
    @ApiResponse(
        responseCode = "200",
        description = "Connect with centrifuge-js's protobuf client and decode AuctionEvent",
        content = @Content(schema = @Schema(implementation = RealtimeResponse.class)))
    @ApiResponse(responseCode = "204", description = "Live updates are off, so poll instead")
    @GetMapping
    public ResponseEntity<RealtimeResponse> get()
    {
        return properties.enabled() ?
            ResponseEntity.ok(new RealtimeResponse(properties.websocketUrl(), properties.channel())) :
            ResponseEntity.noContent().build();
    }
}
