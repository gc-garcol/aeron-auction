package gc.garcol.aeronauction.client.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code POST /auctions}.
 *
 * @param sellerId     of the seller.
 * @param startPrice   the lowest allowed first bid.
 * @param minIncrement the amount each later bid must add.
 * @param durationMs   how long the auction stays open.
 */
public record CreateAuctionRequest(
    @Schema(example = "1") long sellerId,
    @Schema(example = "100") long startPrice,
    @Schema(example = "10") long minIncrement,
    @Schema(example = "60000", description = "From 1,000 (1 second) to 604,800,000 (7 days)") long durationMs)
{
}
