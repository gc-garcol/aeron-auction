package gc.garcol.aeronauction.client.api;

import gc.garcol.aeronauction.client.api.dto.AuctionResponse;
import gc.garcol.aeronauction.client.api.dto.CreateAuctionRequest;
import gc.garcol.aeronauction.client.api.dto.ErrorResponse;
import gc.garcol.aeronauction.client.api.dto.PageResponse;
import gc.garcol.aeronauction.client.api.dto.PlaceBidRequest;
import gc.garcol.aeronauction.protocol.request.Request;
import gc.garcol.aeronauction.protocol.sbe.AuctionStatusFilter;
import gc.garcol.aeronauction.protocol.sbe.SortOrder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * The REST API. Each request becomes one command to the cluster.
 */
@Tag(name = "Auctions")
@RestController
@RequestMapping("/auctions")
@RequiredArgsConstructor
public class AuctionController
{
    private final ClusterGateway gateway;

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
        description = ApiMessages.NOT_SENT,
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
        responseCode = "504",
        description = ApiMessages.OUTCOME_UNKNOWN,
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping
    public CompletableFuture<ResponseEntity<?>> create(@RequestBody final CreateAuctionRequest request)
    {
        return gateway.call(
            new Request.CreateAuction(
                request.sellerId(), request.startPrice(), request.minIncrement(), request.durationMs()),
            HttpStatus.CREATED);
    }

    /**
     * Places a bid.
     *
     * @param id      of the auction.
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
        description = ApiMessages.NOT_FOUND,
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
        responseCode = "409",
        description = "Auction closed, bid too low, or the seller bidding on their own auction",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
        responseCode = "503",
        description = ApiMessages.NOT_SENT,
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
        responseCode = "504",
        description = ApiMessages.OUTCOME_UNKNOWN,
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/{id}/bids")
    public CompletableFuture<ResponseEntity<?>> bid(
        @PathVariable final long id, @RequestBody final PlaceBidRequest request)
    {
        return gateway.call(new Request.PlaceBid(id, request.bidderId(), request.amount()), HttpStatus.OK);
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
        description = ApiMessages.NOT_FOUND,
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
        responseCode = "503",
        description = ApiMessages.NOT_SENT,
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
        responseCode = "504",
        description = ApiMessages.OUTCOME_UNKNOWN,
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{id}")
    public CompletableFuture<ResponseEntity<?>> get(@PathVariable final long id)
    {
        return gateway.call(new Request.GetAuction(id), HttpStatus.OK);
    }

    /**
     * Lists auctions, newest first by default.
     *
     * @param page the zero-based page number.
     * @param size the number of auctions per page.
     * @param status which auctions to include: ALL, OPEN or CLOSED.
     * @param order the direction to sort IDs in: DESC or ASC.
     * @return the page, or an error.
     */
    @Operation(summary = "List auctions, newest first by default")
    @ApiResponse(
        responseCode = "200",
        description = "One page of auctions. Totals count only matches. A page past the last one is empty",
        content = @Content(schema = @Schema(implementation = PageResponse.class)))
    @ApiResponse(
        responseCode = "400",
        description = "Invalid page, size, status or order",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
        responseCode = "503",
        description = ApiMessages.NOT_SENT,
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
        responseCode = "504",
        description = ApiMessages.OUTCOME_UNKNOWN,
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping
    public CompletableFuture<ResponseEntity<?>> list(
        @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") final int page,
        @Parameter(description = "Auctions per page, up to " + Request.ListAuctions.MAX_SIZE)
        @RequestParam(defaultValue = "20") final int size,
        @Parameter(description = "Auctions to include", schema = @Schema(allowableValues = {"ALL", "OPEN", "CLOSED"}))
        @RequestParam(defaultValue = "ALL") final String status,
        @Parameter(description = "The direction to sort IDs in", schema = @Schema(allowableValues = {"DESC", "ASC"}))
        @RequestParam(defaultValue = "DESC") final String order)
    {
        final AuctionStatusFilter statusFilter = parseStatus(status);
        final SortOrder sortOrder = parseOrder(order);
        if (statusFilter == null || sortOrder == null)
        {
            return CompletableFuture.completedFuture(
                ResponseEntity.badRequest().body(new ErrorResponse("INVALID_INPUT")));
        }
        return gateway.call(new Request.ListAuctions(page, size, statusFilter, sortOrder), HttpStatus.OK);
    }

    private static AuctionStatusFilter parseStatus(final String status)
    {
        return switch (status.toUpperCase(Locale.ROOT))
        {
            case "ALL" -> AuctionStatusFilter.ALL;
            case "OPEN" -> AuctionStatusFilter.OPEN;
            case "CLOSED" -> AuctionStatusFilter.CLOSED;
            default -> null;
        };
    }

    private static SortOrder parseOrder(final String order)
    {
        return switch (order.toUpperCase(Locale.ROOT))
        {
            case "ASC" -> SortOrder.ASC;
            case "DESC" -> SortOrder.DESC;
            default -> null;
        };
    }
}
