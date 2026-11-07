package gc.garcol.aeronauction.client.api;

import gc.garcol.aeronauction.client.api.dto.AuctionResponse;
import gc.garcol.aeronauction.client.api.dto.ErrorResponse;
import gc.garcol.aeronauction.client.api.dto.PageResponse;
import gc.garcol.aeronauction.client.rpc.ClusterRpcAgent;
import gc.garcol.aeronauction.client.rpc.RpcFailure;
import gc.garcol.aeronauction.protocol.request.Request;
import gc.garcol.aeronauction.protocol.reply.AuctionPageReply;
import gc.garcol.aeronauction.protocol.reply.AuctionReply;
import gc.garcol.aeronauction.protocol.reply.Reply;
import gc.garcol.aeronauction.protocol.sbe.ReplyStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Sends commands to the cluster and turns each outcome into an HTTP response.
 */
@Component
@RequiredArgsConstructor
final class ClusterGateway
{
    private final ClusterRpcAgent rpc;

    private static ResponseEntity<?> toResponse(final Reply reply, final HttpStatus success)
    {
        final ReplyStatus status = reply.status();
        if (status == ReplyStatus.OK)
        {
            final Object body = switch (reply)
            {
                case AuctionReply auctionReply -> AuctionResponse.of(auctionReply.auction());
                case AuctionPageReply pageReply -> PageResponse.of(pageReply);
            };
            return ResponseEntity.status(success).body(body);
        }

        final HttpStatus httpStatus = switch (status)
        {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case AUCTION_CLOSED, BID_TOO_LOW, SELLER_CANNOT_BID -> HttpStatus.CONFLICT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(httpStatus).body(new ErrorResponse(status.name()));
    }

    private static ResponseEntity<?> toFailureResponse(final Throwable failure)
    {
        final Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
        if (cause instanceof RpcFailure rpcFailure)
        {
            final HttpStatus httpStatus = rpcFailure.kind() == RpcFailure.Kind.TIMEOUT ?
                HttpStatus.GATEWAY_TIMEOUT : HttpStatus.SERVICE_UNAVAILABLE;
            return ResponseEntity.status(httpStatus).body(new ErrorResponse(rpcFailure.getMessage()));
        }
        return ResponseEntity.internalServerError().body(new ErrorResponse("unexpected error"));
    }

    CompletableFuture<ResponseEntity<?>> call(final Request request, final HttpStatus success)
    {
        return rpc.submit(request).handle((reply, failure) ->
            failure != null ? toFailureResponse(failure) : toResponse(reply, success));
    }
}
