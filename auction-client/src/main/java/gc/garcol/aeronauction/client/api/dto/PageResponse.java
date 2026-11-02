package gc.garcol.aeronauction.client.api.dto;

import gc.garcol.aeronauction.protocol.reply.AuctionPageReply;

import java.util.List;

/**
 * One page of auctions as JSON.
 *
 * @param items      the auctions on this page, in ID order, which is empty past the last page.
 * @param page       the zero-based page number.
 * @param size       the requested page size.
 * @param totalItems the number of auctions in all pages.
 * @param totalPages the number of pages.
 */
public record PageResponse(List<AuctionResponse> items, int page, int size, long totalItems, long totalPages)
{
    /**
     * Converts a page from the cluster.
     *
     * @param reply from the cluster.
     * @return the page as JSON.
     */
    public static PageResponse of(final AuctionPageReply reply)
    {
        return new PageResponse(
            reply.items().stream().map(AuctionResponse::of).toList(),
            reply.page(),
            reply.size(),
            reply.totalItems(),
            (reply.totalItems() + reply.size() - 1) / reply.size());
    }
}
