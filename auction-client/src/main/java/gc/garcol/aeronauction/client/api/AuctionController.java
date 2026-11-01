package gc.garcol.aeronauction.client.api;

import gc.garcol.aeronauction.client.rpc.ClusterRpcAgent;
import gc.garcol.aeronauction.client.rpc.RpcFailure;
import gc.garcol.aeronauction.protocol.AuctionPageReply;
import gc.garcol.aeronauction.protocol.AuctionReply;
import gc.garcol.aeronauction.protocol.AuctionView;
import gc.garcol.aeronauction.protocol.Command;
import gc.garcol.aeronauction.protocol.Reply;
import gc.garcol.aeronauction.protocol.sbe.ReplyStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The REST API. Each request becomes one command to the cluster.
 */
@Tag(name = "Auctions")
@RestController
@RequestMapping("/auctions")
public class AuctionController
{
    /**
     * Body of {@code POST /auctions}.
     *
     * @param sellerId of the seller.
     * @param startPrice the lowest allowed first bid.
     * @param minIncrement the amount each later bid must add.
     * @param durationMs how long the auction stays open.
     */
    public record CreateAuctionRequest(
        @Schema(example = "1") long sellerId,
        @Schema(example = "100") long startPrice,
        @Schema(example = "10") long minIncrement,
        @Schema(example = "60000", description = "From 1,000 (1 second) to 604,800,000 (7 days)") long durationMs)
    {
    }

    /**
     * Body of {@code POST /auctions/{id}/bids}.
     *
     * @param bidderId of the bidder.
     * @param amount of the bid.
     */
    public record PlaceBidRequest(
        @Schema(example = "2") long bidderId,
        @Schema(example = "100") long amount)
    {
    }

    /**
     * An auction as JSON. The bid fields are null until someone bids.
     *
     * @param id of the auction.
     * @param sellerId of the seller.
     * @param startPrice the lowest allowed first bid.
     * @param minIncrement the amount each later bid must add.
     * @param highestBid the current highest bid.
     * @param highestBidderId who placed it, and the winner once the auction is closed.
     * @param endsAt the deadline in epoch milliseconds.
     * @param status {@code OPEN} or {@code CLOSED}.
     */
    public record AuctionResponse(
        long id,
        long sellerId,
        long startPrice,
        long minIncrement,
        Long highestBid,
        Long highestBidderId,
        long endsAt,
        String status)
    {
        static AuctionResponse of(final AuctionView auction)
        {
            final boolean hasBid = auction.highestBidderId() != AuctionView.NO_BIDDER;
            return new AuctionResponse(
                auction.id(),
                auction.sellerId(),
                auction.startPrice(),
                auction.minIncrement(),
                hasBid ? auction.highestBid() : null,
                hasBid ? auction.highestBidderId() : null,
                auction.endsAt(),
                auction.closed() ? "CLOSED" : "OPEN");
        }
    }

    /**
     * One page of auctions as JSON.
     *
     * @param items the auctions on this page, in ID order, which is empty past the last page.
     * @param page the zero-based page number.
     * @param size the requested page size.
     * @param totalItems the number of auctions in all pages.
     * @param totalPages the number of pages.
     */
    public record PageResponse(List<AuctionResponse> items, int page, int size, long totalItems, long totalPages)
    {
        static PageResponse of(final AuctionPageReply reply)
        {
            return new PageResponse(
                reply.items().stream().map(AuctionResponse::of).toList(),
                reply.page(),
                reply.size(),
                reply.totalItems(),
                (reply.totalItems() + reply.size() - 1) / reply.size());
        }
    }

    /**
     * Body of every error response.
     *
     * @param error what went wrong.
     */
    public record ErrorResponse(String error)
    {
    }

    private static final String NOT_SENT = "The command was not sent, so it did not happen";
    private static final String OUTCOME_UNKNOWN = "No reply in time. The command may still have applied, " +
        "so check with GET before retrying";
    private static final String NOT_FOUND = "Unknown auction";

    private final ClusterRpcAgent rpc;

    /**
     * Creates the controller.
     *
     * @param rpc used to reach the cluster.
     */
    public AuctionController(final ClusterRpcAgent rpc)
    {
        this.rpc = rpc;
    }

    /**
     * Opens an auction.
     *
     * @param request describing the auction.
     * @return the new auction, or an error.
     */
    @Operation(summary = "Open an auction")
    @ApiResponse(
        responseCode = "201",
        description = "The new auction",
        content = @Content(schema = @Schema(implementation = AuctionResponse.class)))
    @ApiResponse(
        responseCode = "400",
        description = "Invalid input",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
        responseCode = "503",
        description = NOT_SENT,
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
        responseCode = "504",
        description = OUTCOME_UNKNOWN,
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping
    public CompletableFuture<ResponseEntity<?>> create(@RequestBody final CreateAuctionRequest request)
    {
        return call(
            new Command.CreateAuction(
                request.sellerId(), request.startPrice(), request.minIncrement(), request.durationMs()),
            HttpStatus.CREATED);
    }

    /**
     * Places a bid.
     *
     * @param id of the auction.
     * @param request describing the bid.
     * @return the updated auction, or an error.
     */
    @Operation(summary = "Place a bid")
    @ApiResponse(
        responseCode = "200",
        description = "The updated auction",
        content = @Content(schema = @Schema(implementation = AuctionResponse.class)))
    @ApiResponse(
        responseCode = "400",
        description = "Invalid input",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
        responseCode = "404",
        description = NOT_FOUND,
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
        responseCode = "409",
        description = "Auction closed, bid too low, or the seller bidding on their own auction",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
        responseCode = "503",
        description = NOT_SENT,
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
        responseCode = "504",
        description = OUTCOME_UNKNOWN,
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/{id}/bids")
    public CompletableFuture<ResponseEntity<?>> bid(
        @PathVariable final long id, @RequestBody final PlaceBidRequest request)
    {
        return call(new Command.PlaceBid(id, request.bidderId(), request.amount()), HttpStatus.OK);
    }

    /**
     * Reads an auction.
     *
     * @param id of the auction.
     * @return the auction, or an error.
     */
    @Operation(summary = "Read an auction")
    @ApiResponse(
        responseCode = "200",
        description = "The auction. Once it is closed, highestBidderId is the winner",
        content = @Content(schema = @Schema(implementation = AuctionResponse.class)))
    @ApiResponse(
        responseCode = "404",
        description = NOT_FOUND,
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
        responseCode = "503",
        description = NOT_SENT,
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
        responseCode = "504",
        description = OUTCOME_UNKNOWN,
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{id}")
    public CompletableFuture<ResponseEntity<?>> get(@PathVariable final long id)
    {
        return call(new Command.GetAuction(id), HttpStatus.OK);
    }

    /**
     * Lists auctions, oldest first.
     *
     * @param page the zero-based page number.
     * @param size the number of auctions per page.
     * @return the page, or an error.
     */
    @Operation(summary = "List auctions, oldest first")
    @ApiResponse(
        responseCode = "200",
        description = "One page of auctions. A page past the last one is empty",
        content = @Content(schema = @Schema(implementation = PageResponse.class)))
    @ApiResponse(
        responseCode = "400",
        description = "Invalid page or size",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
        responseCode = "503",
        description = NOT_SENT,
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
        responseCode = "504",
        description = OUTCOME_UNKNOWN,
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping
    public CompletableFuture<ResponseEntity<?>> list(
        @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") final int page,
        @Parameter(description = "Auctions per page, up to " + Command.ListAuctions.MAX_SIZE)
        @RequestParam(defaultValue = "20") final int size)
    {
        return call(new Command.ListAuctions(page, size), HttpStatus.OK);
    }

    private CompletableFuture<ResponseEntity<?>> call(final Command command, final HttpStatus success)
    {
        return rpc.submit(command).handle((reply, failure) ->
            failure != null ? toFailureResponse(failure) : toResponse(reply, success));
    }

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
}
