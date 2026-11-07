package gc.garcol.aeronauction.cluster.domain;

import gc.garcol.aeronauction.protocol.request.Request;
import gc.garcol.aeronauction.protocol.model.AuctionView;
import gc.garcol.aeronauction.protocol.sbe.AuctionStatusFilter;
import gc.garcol.aeronauction.protocol.sbe.ReplyStatus;
import gc.garcol.aeronauction.protocol.sbe.SortOrder;

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
public final class AuctionBook
{
    private static final long MIN_DURATION_MS = 1_000;
    private static final long MAX_DURATION_MS = 7L * 24 * 60 * 60 * 1_000;
    private final ArrayList<AuctionView> auctions = new ArrayList<>();
    private long nextAuctionId = 1;
    private int openCount = 0;

    /**
     * Written as a subtraction so a very large bid cannot overflow.
     *
     * @param auction being bid on.
     * @param amount  of the bid.
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
     * @param auction   to read.
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

    /**
     * Opens an auction.
     *
     * @param sellerId     of the seller.
     * @param startPrice   the lowest allowed first bid.
     * @param minIncrement the amount each later bid must add.
     * @param durationMs   how long the auction stays open.
     * @param timestamp    of the current log event.
     * @return the outcome, with the new auction.
     */
    public Result create(
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

    /**
     * Places a bid.
     *
     * @param auctionId of the auction.
     * @param bidderId  of the bidder.
     * @param amount    of the bid.
     * @param timestamp of the current log event.
     * @return the outcome, with the auction.
     */
    public Result placeBid(final long auctionId, final long bidderId, final long amount, final long timestamp)
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

    /**
     * Reads an auction.
     *
     * @param auctionId of the auction.
     * @param timestamp of the current log event.
     * @return the outcome, with the auction.
     */
    public Result get(final long auctionId, final long timestamp)
    {
        final AuctionView auction = find(auctionId);
        return auction == null ? Result.of(ReplyStatus.NOT_FOUND) : new Result(ReplyStatus.OK, at(auction, timestamp));
    }

    /**
     * Reads one page of auctions, sorted by ID. New auctions go at the start of a descending list, so they move later
     * items onto later pages. An auction past its deadline counts as closed, as it reads, even before the tick runs.
     *
     * <p>Without a filter this only visits the page. With one it visits every auction, to count the matches, and it
     * runs on the service thread, so its cost grows with the number of auctions ever opened.
     *
     * @param page      the zero-based page number. A page past the last one is valid, and empty.
     * @param size      the number of auctions per page.
     * @param status    which auctions to include.
     * @param order     the direction to sort IDs in.
     * @param timestamp of the current log event.
     * @return the page, whose total counts only the auctions that match.
     */
    public Page list(
        final int page,
        final int size,
        final AuctionStatusFilter status,
        final SortOrder order,
        final long timestamp)
    {
        if (page < 0 || size < 1 || size > Request.ListAuctions.MAX_SIZE ||
            status == AuctionStatusFilter.NULL_VAL || order == SortOrder.NULL_VAL)
        {
            return new Page(ReplyStatus.INVALID_INPUT, 0, List.of());
        }

        final long start = (long)page * size; // as a long, because a large page number would overflow an int
        final long end = start + size;
        final List<AuctionView> items = new ArrayList<>();
        if (status == AuctionStatusFilter.ALL)
        {
            final long last = Math.min(end, auctions.size());
            for (long position = start; position < last; position++)
            {
                items.add(at(auctionAt((int)position, order), timestamp));
            }
            return new Page(ReplyStatus.OK, auctions.size(), items);
        }

        final boolean wantClosed = status == AuctionStatusFilter.CLOSED;
        long matches = 0;
        for (int position = 0; position < auctions.size(); position++)
        {
            final AuctionView auction = auctionAt(position, order);
            if (isEnded(auction, timestamp) == wantClosed)
            {
                if (matches >= start && matches < end)
                {
                    items.add(at(auction, timestamp));
                }
                matches++;
            }
        }
        return new Page(ReplyStatus.OK, matches, items);
    }

    private AuctionView auctionAt(final int position, final SortOrder order)
    {
        return auctions.get(order == SortOrder.DESC ? auctions.size() - 1 - position : position);
    }

    /**
     * Closes an auction.
     *
     * @param auctionId of the auction to close.
     * @return the closed auction, or null if it was unknown or already closed.
     */
    public AuctionView close(final long auctionId)
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

    /**
     * Counts the open auctions.
     *
     * @return the number of open auctions.
     */
    public int openCount()
    {
        return openCount;
    }

    /**
     * Says which ID the next auction gets.
     *
     * @return the next ID.
     */
    public long nextAuctionId()
    {
        return nextAuctionId;
    }

    /**
     * Returns every auction, in ID order.
     *
     * @return all auctions.
     */
    public Collection<AuctionView> all()
    {
        return auctions;
    }

    /**
     * Visits every open auction, in ID order.
     *
     * @param consumer to call for each one.
     */
    public void forEachOpen(final Consumer<AuctionView> consumer)
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
    public void restoreNextAuctionId(final long nextAuctionId)
    {
        this.nextAuctionId = nextAuctionId;
    }

    /**
     * Restores one auction from a snapshot. Must be called in ID order, which is the order {@link #all()} wrote them.
     *
     * @param auction to restore.
     */
    public void restoreAuction(final AuctionView auction)
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
     * The outcome of a command about one auction.
     *
     * @param status  the outcome.
     * @param auction the affected auction, or {@link AuctionView#NONE} if there is none to show.
     */
    public record Result(ReplyStatus status, AuctionView auction)
    {
        static Result of(final ReplyStatus status)
        {
            return new Result(status, AuctionView.NONE);
        }
    }

    /**
     * One page of auctions.
     *
     * @param status     the outcome.
     * @param totalItems the number of auctions in all pages.
     * @param items      the auctions on this page.
     */
    public record Page(ReplyStatus status, long totalItems, List<AuctionView> items)
    {
    }
}
