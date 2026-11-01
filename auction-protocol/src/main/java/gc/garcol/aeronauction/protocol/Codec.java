package gc.garcol.aeronauction.protocol;

import gc.garcol.aeronauction.protocol.sbe.AuctionDecoder;
import gc.garcol.aeronauction.protocol.sbe.AuctionEncoder;
import gc.garcol.aeronauction.protocol.sbe.AuctionPageReplyDecoder;
import gc.garcol.aeronauction.protocol.sbe.AuctionPageReplyEncoder;
import gc.garcol.aeronauction.protocol.sbe.AuctionReplyDecoder;
import gc.garcol.aeronauction.protocol.sbe.AuctionReplyEncoder;
import gc.garcol.aeronauction.protocol.sbe.BooleanType;
import gc.garcol.aeronauction.protocol.sbe.CreateAuctionDecoder;
import gc.garcol.aeronauction.protocol.sbe.CreateAuctionEncoder;
import gc.garcol.aeronauction.protocol.sbe.GetAuctionDecoder;
import gc.garcol.aeronauction.protocol.sbe.GetAuctionEncoder;
import gc.garcol.aeronauction.protocol.sbe.ListAuctionsDecoder;
import gc.garcol.aeronauction.protocol.sbe.ListAuctionsEncoder;
import gc.garcol.aeronauction.protocol.sbe.MessageHeaderDecoder;
import gc.garcol.aeronauction.protocol.sbe.MessageHeaderEncoder;
import gc.garcol.aeronauction.protocol.sbe.PlaceBidDecoder;
import gc.garcol.aeronauction.protocol.sbe.PlaceBidEncoder;
import gc.garcol.aeronauction.protocol.sbe.ReplyStatus;
import gc.garcol.aeronauction.protocol.sbe.SnapshotAuctionDecoder;
import gc.garcol.aeronauction.protocol.sbe.SnapshotAuctionEncoder;
import gc.garcol.aeronauction.protocol.sbe.SnapshotHeaderDecoder;
import gc.garcol.aeronauction.protocol.sbe.SnapshotHeaderEncoder;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates between the domain records and the SBE messages generated from {@code sbe/auction-schema.xml}.
 *
 * <p>Holds reusable SBE flyweights, so it is not thread-safe. Each owning thread has its own instance.
 */
public final class Codec
{
    /**
     * A decoded command and the ID to reply with.
     *
     * @param correlationId chosen by the client.
     * @param command to apply.
     */
    public record Request(long correlationId, Command command)
    {
    }

    /**
     * The first record of a snapshot.
     *
     * @param nextAuctionId the next ID to assign.
     * @param lastTimestamp of the last log event applied before the snapshot.
     */
    public record SnapshotHeader(long nextAuctionId, long lastTimestamp)
    {
    }

    /**
     * Upper bound on the encoded length of any message.
     */
    public static final int MAX_MESSAGE_LENGTH = 128;
    private static final int HEADER_LENGTH = MessageHeaderDecoder.ENCODED_LENGTH;

    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final CreateAuctionEncoder createEncoder = new CreateAuctionEncoder();
    private final CreateAuctionDecoder createDecoder = new CreateAuctionDecoder();
    private final PlaceBidEncoder bidEncoder = new PlaceBidEncoder();
    private final PlaceBidDecoder bidDecoder = new PlaceBidDecoder();
    private final GetAuctionEncoder getEncoder = new GetAuctionEncoder();
    private final GetAuctionDecoder getDecoder = new GetAuctionDecoder();
    private final ListAuctionsEncoder listEncoder = new ListAuctionsEncoder();
    private final ListAuctionsDecoder listDecoder = new ListAuctionsDecoder();
    private final AuctionPageReplyEncoder pageEncoder = new AuctionPageReplyEncoder();
    private final AuctionPageReplyDecoder pageDecoder = new AuctionPageReplyDecoder();
    private final AuctionReplyEncoder replyEncoder = new AuctionReplyEncoder();
    private final AuctionReplyDecoder replyDecoder = new AuctionReplyDecoder();
    private final SnapshotHeaderEncoder snapshotHeaderEncoder = new SnapshotHeaderEncoder();
    private final SnapshotHeaderDecoder snapshotHeaderDecoder = new SnapshotHeaderDecoder();
    private final SnapshotAuctionEncoder snapshotAuctionEncoder = new SnapshotAuctionEncoder();
    private final SnapshotAuctionDecoder snapshotAuctionDecoder = new SnapshotAuctionDecoder();

    /**
     * Encodes a command at the start of the buffer.
     *
     * @param command to encode.
     * @param correlationId the cluster should reply with.
     * @param buffer to write to.
     * @return the number of bytes written.
     */
    public int encode(final Command command, final long correlationId, final MutableDirectBuffer buffer)
    {
        return HEADER_LENGTH + switch (command)
        {
            case Command.CreateAuction c -> createEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
                .correlationId(correlationId)
                .sellerId(c.sellerId())
                .startPrice(c.startPrice())
                .minIncrement(c.minIncrement())
                .durationMs(c.durationMs())
                .encodedLength();
            case Command.PlaceBid c -> bidEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
                .correlationId(correlationId)
                .auctionId(c.auctionId())
                .bidderId(c.bidderId())
                .amount(c.amount())
                .encodedLength();
            case Command.GetAuction c -> getEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
                .correlationId(correlationId)
                .auctionId(c.auctionId())
                .encodedLength();
            case Command.ListAuctions c -> listEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
                .correlationId(correlationId)
                .page(c.page())
                .size(c.size())
                .encodedLength();
        };
    }

    /**
     * Decodes a command received by the cluster.
     *
     * @param buffer to read from.
     * @param offset of the message.
     * @param length of the message.
     * @return the decoded request.
     * @throws IllegalArgumentException if the message is malformed. The cluster drops such messages rather than
     * crashing, because input comes from outside the replicated state machine.
     */
    public Request decodeRequest(final DirectBuffer buffer, final int offset, final int length)
    {
        final int templateId = wrapHeader(buffer, offset, length);
        final int body = offset + HEADER_LENGTH;
        final int blockLength = headerDecoder.blockLength();
        final int version = headerDecoder.version();

        return switch (templateId)
        {
            case CreateAuctionDecoder.TEMPLATE_ID ->
            {
                createDecoder.wrap(buffer, body, blockLength, version);
                yield new Request(createDecoder.correlationId(), new Command.CreateAuction(
                    createDecoder.sellerId(),
                    createDecoder.startPrice(),
                    createDecoder.minIncrement(),
                    createDecoder.durationMs()));
            }
            case PlaceBidDecoder.TEMPLATE_ID ->
            {
                bidDecoder.wrap(buffer, body, blockLength, version);
                yield new Request(bidDecoder.correlationId(), new Command.PlaceBid(
                    bidDecoder.auctionId(), bidDecoder.bidderId(), bidDecoder.amount()));
            }
            case GetAuctionDecoder.TEMPLATE_ID ->
            {
                getDecoder.wrap(buffer, body, blockLength, version);
                yield new Request(getDecoder.correlationId(), new Command.GetAuction(getDecoder.auctionId()));
            }
            case ListAuctionsDecoder.TEMPLATE_ID ->
            {
                listDecoder.wrap(buffer, body, blockLength, version);
                yield new Request(
                    listDecoder.correlationId(), new Command.ListAuctions(listDecoder.page(), listDecoder.size()));
            }
            default -> throw new IllegalArgumentException("unknown template " + templateId);
        };
    }

    /**
     * Encodes a reply at the start of the buffer.
     *
     * @param reply to encode.
     * @param buffer to write to.
     * @return the number of bytes written.
     */
    public int encodeReply(final AuctionReply reply, final MutableDirectBuffer buffer)
    {
        replyEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .correlationId(reply.correlationId())
            .status(reply.status());
        write(replyEncoder.auction(), reply.auction());
        return HEADER_LENGTH + replyEncoder.encodedLength();
    }

    /**
     * Encodes a page of auctions at the start of the buffer.
     *
     * @param reply to encode.
     * @param buffer to write to.
     * @return the number of bytes written.
     */
    public int encodePageReply(final AuctionPageReply reply, final MutableDirectBuffer buffer)
    {
        pageEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .correlationId(reply.correlationId())
            .status(reply.status())
            .page(reply.page())
            .size(reply.size())
            .totalItems(reply.totalItems());

        final AuctionPageReplyEncoder.ItemsEncoder items = pageEncoder.itemsCount(reply.items().size());
        for (final AuctionView auction : reply.items())
        {
            write(items.next().auction(), auction);
        }
        return HEADER_LENGTH + pageEncoder.encodedLength();
    }

    /**
     * Decodes a reply received by the client.
     *
     * @param buffer to read from.
     * @param offset of the message.
     * @param length of the message.
     * @return the decoded reply.
     */
    public Reply decodeReply(final DirectBuffer buffer, final int offset, final int length)
    {
        final int templateId = wrapHeader(buffer, offset, length);
        final int body = offset + HEADER_LENGTH;
        return switch (templateId)
        {
            case AuctionReplyDecoder.TEMPLATE_ID ->
            {
                replyDecoder.wrap(buffer, body, headerDecoder.blockLength(), headerDecoder.version());
                yield new AuctionReply(
                    replyDecoder.correlationId(), replyDecoder.status(), read(replyDecoder.auction()));
            }
            case AuctionPageReplyDecoder.TEMPLATE_ID ->
            {
                pageDecoder.wrap(buffer, body, headerDecoder.blockLength(), headerDecoder.version());
                yield decodePage();
            }
            default -> throw new IllegalArgumentException("unexpected template " + templateId);
        };
    }

    /**
     * Encodes the first record of a snapshot at the start of the buffer.
     *
     * @param nextAuctionId the next ID to assign.
     * @param lastTimestamp of the last log event applied.
     * @param buffer to write to.
     * @return the number of bytes written.
     */
    public int encodeSnapshotHeader(
        final long nextAuctionId, final long lastTimestamp, final MutableDirectBuffer buffer)
    {
        return HEADER_LENGTH + snapshotHeaderEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .nextAuctionId(nextAuctionId)
            .lastTimestamp(lastTimestamp)
            .encodedLength();
    }

    /**
     * Encodes one auction of a snapshot at the start of the buffer.
     *
     * @param auction to encode.
     * @param buffer to write to.
     * @return the number of bytes written.
     */
    public int encodeSnapshotAuction(final AuctionView auction, final MutableDirectBuffer buffer)
    {
        snapshotAuctionEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        write(snapshotAuctionEncoder.auction(), auction);
        return HEADER_LENGTH + snapshotAuctionEncoder.encodedLength();
    }

    /**
     * Decodes one snapshot record.
     *
     * @param buffer to read from.
     * @param offset of the record.
     * @param length of the record.
     * @return a {@link SnapshotHeader} or an {@link AuctionView}.
     */
    public Object decodeSnapshotRecord(final DirectBuffer buffer, final int offset, final int length)
    {
        final int templateId = wrapHeader(buffer, offset, length);
        final int body = offset + HEADER_LENGTH;
        if (templateId == SnapshotHeaderDecoder.TEMPLATE_ID)
        {
            snapshotHeaderDecoder.wrap(buffer, body, headerDecoder.blockLength(), headerDecoder.version());
            return new SnapshotHeader(snapshotHeaderDecoder.nextAuctionId(), snapshotHeaderDecoder.lastTimestamp());
        }
        snapshotAuctionDecoder.wrap(buffer, body, headerDecoder.blockLength(), headerDecoder.version());
        return read(snapshotAuctionDecoder.auction());
    }

    private AuctionPageReply decodePage()
    {
        // SBE fields must be read in schema order, and the group comes last.
        final long correlationId = pageDecoder.correlationId();
        final ReplyStatus status = pageDecoder.status();
        final int page = pageDecoder.page();
        final int size = pageDecoder.size();
        final long totalItems = pageDecoder.totalItems();

        final AuctionPageReplyDecoder.ItemsDecoder group = pageDecoder.items();
        final List<AuctionView> items = new ArrayList<>(group.count());
        while (group.hasNext())
        {
            items.add(read(group.next().auction()));
        }
        return new AuctionPageReply(correlationId, status, page, size, totalItems, items);
    }

    private int wrapHeader(final DirectBuffer buffer, final int offset, final int length)
    {
        if (length < HEADER_LENGTH)
        {
            throw new IllegalArgumentException("message too short: " + length);
        }
        headerDecoder.wrap(buffer, offset);
        if (headerDecoder.schemaId() != MessageHeaderDecoder.SCHEMA_ID ||
            length < HEADER_LENGTH + headerDecoder.blockLength())
        {
            throw new IllegalArgumentException("not a valid auction message");
        }
        return headerDecoder.templateId();
    }

    private static void write(final AuctionEncoder encoder, final AuctionView auction)
    {
        encoder
            .id(auction.id())
            .sellerId(auction.sellerId())
            .startPrice(auction.startPrice())
            .minIncrement(auction.minIncrement())
            .highestBid(auction.highestBid())
            .highestBidderId(auction.highestBidderId())
            .endsAt(auction.endsAt())
            .closed(auction.closed() ? BooleanType.TRUE : BooleanType.FALSE);
    }

    private static AuctionView read(final AuctionDecoder decoder)
    {
        return new AuctionView(
            decoder.id(),
            decoder.sellerId(),
            decoder.startPrice(),
            decoder.minIncrement(),
            decoder.highestBid(),
            decoder.highestBidderId(),
            decoder.endsAt(),
            decoder.closed() == BooleanType.TRUE);
    }
}
