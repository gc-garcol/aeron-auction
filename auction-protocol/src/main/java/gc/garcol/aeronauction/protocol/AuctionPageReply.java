package gc.garcol.aeronauction.protocol;

import gc.garcol.aeronauction.protocol.sbe.ReplyStatus;

import java.util.List;

/**
 * One page of auctions, in ID order.
 *
 * @param correlationId copied from the request.
 * @param status the outcome.
 * @param page the zero-based page number that was requested.
 * @param size the page size that was requested.
 * @param totalItems the number of auctions in all pages.
 * @param items the auctions on this page, which is empty past the last page.
 */
public record AuctionPageReply(
    long correlationId,
    ReplyStatus status,
    int page,
    int size,
    long totalItems,
    List<AuctionView> items) implements Reply
{
}
