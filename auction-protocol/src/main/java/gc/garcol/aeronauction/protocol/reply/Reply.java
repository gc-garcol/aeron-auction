package gc.garcol.aeronauction.protocol.reply;

import gc.garcol.aeronauction.protocol.request.Request;
import gc.garcol.aeronauction.protocol.sbe.ReplyStatus;

/**
 * The cluster's answer to a {@link Request}.
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
