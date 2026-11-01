package gc.garcol.aeronauction.protocol;

import gc.garcol.aeronauction.protocol.sbe.ReplyStatus;

/**
 * The cluster's answer to a {@link Command} about one auction.
 *
 * @param correlationId copied from the request.
 * @param status the outcome.
 * @param auction the affected auction, or {@link AuctionView#NONE} if there is none to show.
 */
public record AuctionReply(long correlationId, ReplyStatus status, AuctionView auction) implements Reply
{
}
