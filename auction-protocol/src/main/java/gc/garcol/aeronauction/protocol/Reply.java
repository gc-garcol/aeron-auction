package gc.garcol.aeronauction.protocol;

import gc.garcol.aeronauction.protocol.sbe.ReplyStatus;

/**
 * The cluster's answer to a {@link Command}.
 */
public sealed interface Reply permits AuctionReply, AuctionPageReply
{
    /**
     * Identifies the command this answers.
     *
     * @return the ID copied from the request.
     */
    long correlationId();

    /**
     * Says how the command went.
     *
     * @return the outcome.
     */
    ReplyStatus status();
}
