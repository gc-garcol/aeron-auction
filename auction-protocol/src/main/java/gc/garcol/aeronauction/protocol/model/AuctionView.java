package gc.garcol.aeronauction.protocol.model;

/**
 * Immutable picture of an auction. Amounts are minor units. A {@code highestBidderId} of {@link #NO_BIDDER}
 * means nobody has bid, in which case {@code highestBid} is 0.
 *
 * @param id              of the auction.
 * @param sellerId        of the seller.
 * @param startPrice      the lowest allowed first bid.
 * @param minIncrement    the amount each later bid must add.
 * @param highestBid      the current highest bid.
 * @param highestBidderId who placed the highest bid.
 * @param endsAt          the deadline in cluster time, in milliseconds.
 * @param closed          true if the auction has ended.
 */
public record AuctionView(
    long id,
    long sellerId,
    long startPrice,
    long minIncrement,
    long highestBid,
    long highestBidderId,
    long endsAt,
    boolean closed)
{
    /**
     * Value of {@code highestBidderId} when nobody has bid.
     */
    public static final long NO_BIDDER = 0;

    /**
     * Placeholder for replies that have no auction to show.
     */
    public static final AuctionView NONE = new AuctionView(0, 0, 0, 0, 0, NO_BIDDER, 0, false);
}
