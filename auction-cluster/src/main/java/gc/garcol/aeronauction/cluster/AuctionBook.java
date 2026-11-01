package gc.garcol.aeronauction.cluster;

import gc.garcol.aeronauction.protocol.AuctionView;
import gc.garcol.aeronauction.protocol.Command;
import gc.garcol.aeronauction.protocol.sbe.ReplyStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * The auction rules, with no Aeron dependency. Time always comes from the caller, which passes the timestamp from
 * the replicated log, so every node reaches the same state.
 *
 * <p>IDs run 1, 2, 3... and auctions are never removed, so a list indexed by {@code id - 1} gives direct lookups
 * without boxing, and iterates in ID order on every node. Removing auctions would need a different structure.
 */
final class AuctionBook
{
    static final long MIN_DURATION_MS = 1_000;
    static final long MAX_DURATION_MS = 7L * 24 * 60 * 60 * 1_000;

    record Result(ReplyStatus status, AuctionView auction)
    {
        static Result of(final ReplyStatus status)
        {
            return new Result(status, AuctionView.NONE);
        }
    }

    record Page(ReplyStatus status, long totalItems, List<AuctionView> items)
    {
    }

    private final ArrayList<AuctionView> auctions = new ArrayList<>();
    private long nextAuctionId = 1;
    private int openCount = 0;

    Result create(
        final long sellerId,
        final long startPrice,
        final long minIncrement,
        final long durationMs,
        final long timestamp)
    {
        if (sellerId <= 0 || startPrice <= 0 || minIncrement <= 0 ||
            durationMs < MIN_DURATION_MS || durationMs > MAX_DURATION_MS)
        {
            return Result.of(ReplyStatus.INVALID_INPUT);
        }

        final AuctionView auction = new AuctionView(
            nextAuctionId++,
            sellerId,
            startPrice,
            minIncrement,
            0,
            AuctionView.NO_BIDDER,
            timestamp + durationMs,
            false);
        auctions.add(auction);
        openCount++;
        return new Result(ReplyStatus.OK, auction);
    }

    Result placeBid(final long auctionId, final long bidderId, final long amount, final long timestamp)
    {
        final AuctionView auction = find(auctionId);
        if (auction == null)
        {
            return Result.of(ReplyStatus.NOT_FOUND);
        }
        if (bidderId <= 0 || amount <= 0)
        {
            return new Result(ReplyStatus.INVALID_INPUT, at(auction, timestamp));
        }
        if (isEnded(auction, timestamp))
        {
            return new Result(ReplyStatus.AUCTION_CLOSED, at(auction, timestamp));
        }
        if (bidderId == auction.sellerId())
        {
            return new Result(ReplyStatus.SELLER_CANNOT_BID, auction);
        }
        if (!beatsCurrentPrice(auction, amount))
        {
            return new Result(ReplyStatus.BID_TOO_LOW, auction);
        }

        final AuctionView updated = new AuctionView(
            auction.id(),
            auction.sellerId(),
            auction.startPrice(),
            auction.minIncrement(),
            amount,
            bidderId,
            auction.endsAt(),
            false);
        replace(updated);
        return new Result(ReplyStatus.OK, updated);
    }

    Result get(final long auctionId, final long timestamp)
    {
        final AuctionView auction = find(auctionId);
        return auction == null ? Result.of(ReplyStatus.NOT_FOUND) : new Result(ReplyStatus.OK, at(auction, timestamp));
    }

    /**
     * Reads one page of auctions, in ID order. Pages stay stable as auctions are added, because new ones go at the end.
     *
     * @param page the zero-based page number. A page past the last one is valid, and empty.
     * @param size the number of auctions per page.
     * @param timestamp of the current log event.
     * @return the page.
     */
    Page list(final int page, final int size, final long timestamp)
    {
        if (page < 0 || size < 1 || size > Command.ListAuctions.MAX_SIZE)
        {
            return new Page(ReplyStatus.INVALID_INPUT, 0, List.of());
        }

        final long start = (long)page * size; // as a long, because a large page number would overflow an int
        final long end = Math.min(start + size, auctions.size());
        final List<AuctionView> items = new ArrayList<>();
        for (long i = start; i < end; i++)
        {
            items.add(at(auctions.get((int)i), timestamp));
        }
        return new Page(ReplyStatus.OK, auctions.size(), items);
    }

    /**
     * Closes an auction.
     *
     * @param auctionId of the auction to close.
     * @return the closed auction, or null if it was unknown or already closed.
     */
    AuctionView close(final long auctionId)
    {
        final AuctionView auction = find(auctionId);
        if (auction == null || auction.closed())
        {
            return null;
        }
        final AuctionView closed = asClosed(auction);
        replace(closed);
        openCount--;
        return closed;
    }

    int openCount()
    {
        return openCount;
    }

    long nextAuctionId()
    {
        return nextAuctionId;
    }

    Collection<AuctionView> all()
    {
        return auctions;
    }

    void forEachOpen(final Consumer<AuctionView> consumer)
    {
        for (final AuctionView auction : auctions)
        {
            if (!auction.closed())
            {
                consumer.accept(auction);
            }
        }
    }

    /**
     * Restores the ID counter from a snapshot. Call {@link #restoreAuction} for each auction as well.
     *
     * @param nextAuctionId the next ID to assign.
     */
    void restoreNextAuctionId(final long nextAuctionId)
    {
        this.nextAuctionId = nextAuctionId;
    }

    /**
     * Restores one auction from a snapshot. Must be called in ID order, which is the order {@link #all()} wrote them.
     *
     * @param auction to restore.
     */
    void restoreAuction(final AuctionView auction)
    {
        auctions.add(auction);
        if (!auction.closed())
        {
            openCount++;
        }
    }

    /**
     * Finds an auction. The ID comes from outside the cluster, so it is checked before narrowing to an index.
     *
     * @param auctionId of the auction.
     * @return the auction, or null if there is none with that ID.
     */
    private AuctionView find(final long auctionId)
    {
        return auctionId < 1 || auctionId > auctions.size() ? null : auctions.get((int)(auctionId - 1));
    }

    private void replace(final AuctionView auction)
    {
        auctions.set((int)(auction.id() - 1), auction);
    }

    /**
     * Written as a subtraction so a very large bid cannot overflow.
     *
     * @param auction being bid on.
     * @param amount of the bid.
     * @return true if the bid is high enough.
     */
    private static boolean beatsCurrentPrice(final AuctionView auction, final long amount)
    {
        return auction.highestBidderId() == AuctionView.NO_BIDDER ?
            amount >= auction.startPrice() :
            amount - auction.highestBid() >= auction.minIncrement();
    }

    private static boolean isEnded(final AuctionView auction, final long timestamp)
    {
        return auction.closed() || timestamp >= auction.endsAt();
    }

    /**
     * An auction past its deadline reads as closed even if the tick has not processed it yet.
     *
     * @param auction to read.
     * @param timestamp of the current log event.
     * @return the auction as it should appear at this time.
     */
    private static AuctionView at(final AuctionView auction, final long timestamp)
    {
        return !auction.closed() && isEnded(auction, timestamp) ? asClosed(auction) : auction;
    }

    private static AuctionView asClosed(final AuctionView auction)
    {
        return new AuctionView(
            auction.id(),
            auction.sellerId(),
            auction.startPrice(),
            auction.minIncrement(),
            auction.highestBid(),
            auction.highestBidderId(),
            auction.endsAt(),
            true);
    }
}
