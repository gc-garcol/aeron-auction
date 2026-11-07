'use strict';

// Decodes AuctionEvent from auction-client/src/main/proto/auction_event.proto, which arrives from Centrifugo as the
// bytes of a binary WebSocket frame. Keep the field numbers in step with that file. Unknown fields are skipped, so
// adding a field there doesn't break an older page.
//
// int64 values become Numbers, which stay exact up to Number.MAX_SAFE_INTEGER, the same limit as the REST API's JSON.
const AuctionEvent = (() => {
  const TYPES = { 1: 'CREATED', 2: 'BID_PLACED' };
  const FIELDS = {
    1: 'type',
    2: 'id',
    3: 'sellerId',
    4: 'startPrice',
    5: 'minIncrement',
    6: 'endsAt',
    7: 'highestBid',
    8: 'highestBidderId',
  };

  function decode(bytes) {
    let pos = 0;

    function varint() {
      let value = 0;
      let scale = 1;
      for (;;) {
        if (pos >= bytes.length) {
          throw new Error('truncated AuctionEvent');
        }
        const byte = bytes[pos++];
        value += (byte & 0x7f) * scale;
        if ((byte & 0x80) === 0) {
          return value;
        }
        scale *= 128;
      }
    }

    const event = { highestBid: null, highestBidderId: null };
    while (pos < bytes.length) {
      const key = varint();
      const field = Math.floor(key / 8);
      const wireType = key % 8;
      if (wireType === 0) {
        const value = varint();
        if (FIELDS[field]) {
          event[FIELDS[field]] = value;
        }
      } else if (wireType === 1) {
        pos += 8;
      } else if (wireType === 2) {
        pos += varint();
      } else if (wireType === 5) {
        pos += 4;
      } else {
        throw new Error(`unsupported wire type ${wireType} in AuctionEvent`);
      }
    }
    event.type = TYPES[event.type] ?? 'UNKNOWN';
    return event;
  }

  // The same shape as an auction from the REST API. Events only follow successful writes, so the auction is open.
  function toAuction(event) {
    return {
      id: event.id,
      sellerId: event.sellerId ?? 0,
      startPrice: event.startPrice ?? 0,
      minIncrement: event.minIncrement ?? 0,
      highestBid: event.highestBid,
      highestBidderId: event.highestBidderId,
      endsAt: event.endsAt ?? 0,
      status: 'OPEN',
    };
  }

  return { decode, toAuction };
})();
