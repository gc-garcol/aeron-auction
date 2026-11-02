package gc.garcol.aeronauction.client.api.dto;

import gc.garcol.aeronauction.protocol.model.AuctionView;

/**
 * An auction as JSON. The bid fields are null until someone bids.
 *
 * @param id              of the auction.
 * @param sellerId        of the seller.
 * @param startPrice      the lowest allowed first bid.
 * @param minIncrement    the amount each later bid must add.
 * @param highestBid      the current highest bid.
 * @param highestBidderId who placed it, and the winner once the auction is closed.
 * @param endsAt          the deadline in epoch milliseconds.
 * @param status          {@code OPEN} or {@code CLOSED}.
 */
public record AuctionResponse(
    long id,
    long sellerId,
    long startPrice,
    long minIncrement,
    Long highestBid,
    Long highestBidderId,
    long endsAt,
    String status)
{
    /**
     * Converts an auction from the cluster.
     *
     * @param auction from the cluster.
     * @return the auction as JSON.
     */
    public static AuctionResponse of(final AuctionView auction)
    {
        final boolean hasBid = auction.highestBidderId() != AuctionView.NO_BIDDER;
        return new AuctionResponse(
            auction.id(),
            auction.sellerId(),
            auction.startPrice(),
            auction.minIncrement(),
            hasBid ? auction.highestBid() : null,
            hasBid ? auction.highestBidderId() : null,
            auction.endsAt(),
            auction.closed() ? "CLOSED" : "OPEN");
    }
}
