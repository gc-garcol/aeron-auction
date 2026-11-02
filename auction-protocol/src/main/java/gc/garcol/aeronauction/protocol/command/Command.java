package gc.garcol.aeronauction.protocol.command;

import gc.garcol.aeronauction.protocol.sbe.AuctionStatusFilter;
import gc.garcol.aeronauction.protocol.sbe.SortOrder;

/** A request from the client to the cluster. */
public sealed interface Command
{
    /**
     * Opens a new auction.
     *
     * @param sellerId     of the seller.
     * @param startPrice   the lowest allowed first bid.
     * @param minIncrement the amount each later bid must add.
     * @param durationMs   how long the auction stays open.
     */
    record CreateAuction(long sellerId, long startPrice, long minIncrement, long durationMs) implements Command
    {
    }

    /**
     * Places a bid.
     *
     * @param auctionId of the auction.
     * @param bidderId  of the bidder.
     * @param amount    of the bid.
     */
    record PlaceBid(long auctionId, long bidderId, long amount) implements Command
    {
    }

    /**
     * Reads an auction.
     *
     * @param auctionId of the auction.
     */
    record GetAuction(long auctionId) implements Command
    {
    }

    /**
     * Reads one page of auctions, sorted by ID.
     *
     * @param page the zero-based page number.
     * @param size the number of auctions per page, up to {@link #MAX_SIZE}.
     * @param status which auctions to include.
     * @param order the direction to sort IDs in.
     */
    record ListAuctions(int page, int size, AuctionStatusFilter status, SortOrder order) implements Command
    {
        /**
         * The largest page. The cluster sends a page as one message, so this keeps it within Aeron's message limit.
         */
        public static final int MAX_SIZE = 100;
    }
}
