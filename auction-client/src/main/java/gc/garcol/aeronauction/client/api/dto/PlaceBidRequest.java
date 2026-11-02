package gc.garcol.aeronauction.client.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code POST /auctions/{id}/bids}.
 *
 * @param bidderId of the bidder.
 * @param amount   of the bid.
 */
public record PlaceBidRequest(
    @Schema(example = "2") long bidderId,
    @Schema(example = "100") long amount)
{
}
