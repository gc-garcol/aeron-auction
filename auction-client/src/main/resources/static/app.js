'use strict';

const { createApp } = Vue;

const REFRESH_MS = 2000;
// With live updates, polling is only a safety net for a lost event, so it can be slow.
const LIVE_REFRESH_MS = 15000;
// Coalesces a burst of new auctions, or of auctions reaching their deadline, into one refresh.
const LIVE_REFRESH_DELAY_MS = 300;
const SUCCESS_MESSAGE_MS = 5000;
const ENDING_SOON_MS = 10000;
const MAX_DURATION_SECONDS = 604800;
const BIDDER_KEY = 'auction.bidderId';
const STACKED_WIDTH_PX = 900;
const FLASH_MS = 800;

const REASONS = {
  INVALID_INPUT: 'Those values aren\'t valid. Check them and try again.',
  NOT_FOUND: 'That auction doesn\'t exist.',
  AUCTION_CLOSED: 'That auction has closed.',
  BID_TOO_LOW: 'That bid is too low. Another bid may have just come in.',
  SELLER_CANNOT_BID: 'Sellers can\'t bid on their own auction.',
};

async function api(path, body) {
  const options = body === undefined ? {} : {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  };
  try {
    const response = await fetch(path, options);
    const data = await response.json().catch(() => null);
    return { ok: response.ok, status: response.status, data };
  } catch (error) {
    return { ok: false, status: 0, data: null };
  }
}

function describeFailure(result) {
  if (result.status === 0) {
    return { text: 'Can\'t reach the server.', tone: 'bad' };
  }
  if (result.status === 503) {
    return { text: 'The cluster is unavailable, so nothing was changed. Try again shortly.', tone: 'bad' };
  }
  if (result.status === 504) {
    return {
      text: 'No reply in time, so this may still have gone through. Check the list before trying again.',
      tone: 'warn',
    };
  }
  const error = result.data && result.data.error;
  return { text: REASONS[error] || `Request failed (${result.status}).`, tone: 'bad' };
}

// Amounts stay exact only up to Number.MAX_SAFE_INTEGER, because JavaScript numbers are doubles.
function isPositiveWhole(text) {
  const value = Number(text);
  return text !== '' && Number.isSafeInteger(value) && value > 0;
}

function loadBidder() {
  try {
    return localStorage.getItem(BIDDER_KEY) || '2';
  } catch (error) {
    return '2'; // browser storage can be blocked, and remembering the ID is only a convenience
  }
}

createApp({
  data() {
    return {
      auctions: [],
      page: 0, // the page being requested
      shownPage: 0, // the page on screen, which only changes once its auctions have arrived
      size: 20,
      sizes: [10, 20, 50, 100],
      statusFilter: 'ALL',
      order: 'DESC',
      statusOptions: [
        { value: 'ALL', label: 'All' },
        { value: 'OPEN', label: 'Open' },
        { value: 'CLOSED', label: 'Closed' },
      ],
      totalItems: 0,
      totalPages: 0,
      loaded: false,
      connection: 'connecting',
      live: 'off', // off, connecting, up or down; see startLive
      now: Date.now(),
      message: null,
      bidder: loadBidder(),
      form: { sellerId: '1', startPrice: '100', minIncrement: '10', durationSeconds: '60' },
      formErrors: {},
      creating: false,
      panelTab: 'bid',
      selectedId: null,
      flashing: {},
      bidSteps: [0, 1, 5, 10],
      createFields: [
        { key: 'sellerId', label: 'Seller ID' },
        { key: 'startPrice', label: 'Start price' },
        { key: 'minIncrement', label: 'Minimum increment' },
        { key: 'durationSeconds', label: 'Duration in seconds' },
      ],
      findQuery: '',
      findError: '',
      finding: false,
      foundId: null, // while set, the list shows only this auction
      // Keyed by auction ID and separate from the list, so a refresh never clears a bid someone is typing.
      drafts: {},
      bidErrors: {},
      sending: {},
    };
  },

  computed: {
    connectionText() {
      return { up: 'Cluster connected', down: 'Cluster unavailable', connecting: 'Connecting…' }[this.connection];
    },
    liveText() {
      return { up: 'Live', connecting: 'Live: connecting…', down: 'Live: polling' }[this.live] ?? null;
    },
    bidderValid() {
      return isPositiveWhole(this.bidder);
    },
    emptyText() {
      return { ALL: 'No auctions yet.', OPEN: 'No open auctions.', CLOSED: 'No closed auctions.' }[this.statusFilter];
    },
    selected() {
      return this.auctions.find((auction) => auction.id === this.selectedId) ?? null;
    },
  },

  watch: {
    bidder(value) {
      try {
        localStorage.setItem(BIDDER_KEY, value);
      } catch (error) {
        // see loadBidder
      }
    },
  },

  mounted() {
    this.refreshGeneration = 0;
    this.lastBids = {};
    this.endedRefreshed = new Set();
    this.clock = setInterval(() => {
      this.now = Date.now();
      this.refreshEnded();
    }, 1000);
    document.addEventListener('visibilitychange', this.onVisibilityChange);
    this.refresh();
    this.startLive();
  },

  methods: {
    digitsOnly(text) {
      return String(text ?? '').replace(/\D/g, '');
    },

    formatAmount(amount) {
      return Number(amount).toLocaleString('en-US');
    },
    currentPrice(auction) {
      return this.formatAmount(auction.highestBidderId === null ? auction.startPrice : auction.highestBid);
    },

    selectAuction(auction) {
      this.selectedId = auction.id;
      this.panelTab = 'bid';
      if (window.innerWidth <= STACKED_WIDTH_PX) {
        this.$refs.panel.scrollIntoView({ block: 'start' });
      }
    },
    fillBid(auction, steps) {
      this.drafts[auction.id] = String(this.minimumBid(auction) + steps * auction.minIncrement);
      this.bidErrors[auction.id] = '';
    },

    // The selection only moves when its auction has left the list, so a refresh never changes what someone is bidding on.
    showAuctions(auctions) {
      this.flashNewBids(auctions);
      this.auctions = auctions;
      if (!this.selected) {
        const next = auctions.find((auction) => !this.isClosed(auction)) ?? auctions[0];
        this.selectedId = next ? next.id : null;
      }
    },
    flashNewBids(auctions) {
      for (const { id, highestBid } of auctions) {
        const known = id in this.lastBids;
        if (known && this.lastBids[id] !== highestBid) {
          this.flashing[id] = true;
          setTimeout(() => {
            delete this.flashing[id];
          }, FLASH_MS);
        }
        this.lastBids[id] = highestBid;
      }
    },

    isClosed(auction) {
      return auction.status === 'CLOSED';
    },
    isSeller(auction) {
      return this.bidderValid && Number(this.bidder) === auction.sellerId;
    },
    isLeading(auction) {
      return this.bidderValid && Number(this.bidder) === auction.highestBidderId;
    },
    endingSoon(auction) {
      return auction.endsAt - this.now < ENDING_SOON_MS;
    },
    minimumBid(auction) {
      return auction.highestBidderId === null ? auction.startPrice : auction.highestBid + auction.minIncrement;
    },

    badge(auction) {
      if (this.isClosed(auction)) {
        return { text: 'Closed', tone: 'closed' };
      }
      if (this.isLeading(auction)) {
        return { text: 'You\'re leading', tone: 'leading' };
      }
      return this.endingSoon(auction) ? { text: 'Ending soon', tone: 'soon' } : { text: 'Open', tone: 'open' };
    },
    priceCaption(auction) {
      if (auction.highestBidderId === null) {
        return this.isClosed(auction) ? 'start price, no bids' : 'start price, no bids yet';
      }
      return `highest bid, by ${auction.highestBidderId}`;
    },
    outcome(auction) {
      return auction.highestBidderId === null ?
        'Ended with no bids' : `Won by ${auction.highestBidderId} for ${this.formatAmount(auction.highestBid)}`;
    },
    timeLeft(auction) {
      const seconds = Math.ceil((auction.endsAt - this.now) / 1000);
      if (seconds <= 0) {
        return 'Ending…';
      }
      const days = Math.floor(seconds / 86400);
      const hours = Math.floor((seconds % 86400) / 3600);
      const minutes = Math.floor((seconds % 3600) / 60);
      if (days > 0) {
        return `${days}d ${hours}h`;
      }
      if (hours > 0) {
        return `${hours}h ${minutes}m`;
      }
      return minutes > 0 ? `${minutes}m ${seconds % 60}s` : `${seconds}s`;
    },

    showMessage(text, tone) {
      this.message = { text, tone };
      clearTimeout(this.messageTimer);
      if (tone === 'good') { // problems stay until they're replaced, because they may need acting on
        this.messageTimer = setTimeout(() => {
          this.message = null;
        }, SUCCESS_MESSAGE_MS);
      }
    },

    async refresh() {
      clearTimeout(this.refreshTimer);
      const generation = ++this.refreshGeneration;
      const path = this.foundId === null ?
        `auctions?page=${this.page}&size=${this.size}&status=${this.statusFilter}&order=${this.order}` :
        `auctions/${this.foundId}`;
      const result = await api(path);
      if (generation !== this.refreshGeneration) {
        return; // a newer refresh started, such as after changing page
      }

      if (result.ok && this.foundId !== null) {
        this.showAuctions([result.data]);
        this.connection = 'up';
      } else if (result.ok) {
        if (this.page > 0 && this.page >= result.data.totalPages) {
          this.page = Math.max(result.data.totalPages - 1, 0);
          this.refresh();
          return;
        }
        this.showAuctions(result.data.items);
        this.shownPage = this.page;
        this.totalItems = result.data.totalItems;
        this.totalPages = result.data.totalPages;
        this.loaded = true;
        this.connection = 'up';
      } else {
        this.connection = 'down';
      }
      this.schedule();
    },

    // Don't poll while the tab isn't visible. Live updates keep arriving, and a refresh runs when it's shown again.
    schedule() {
      clearTimeout(this.refreshTimer);
      if (!document.hidden) {
        this.refreshTimer = setTimeout(this.refresh, this.live === 'up' ? LIVE_REFRESH_MS : REFRESH_MS);
      }
    },

    refreshSoon() {
      clearTimeout(this.liveRefreshTimer);
      this.liveRefreshTimer = setTimeout(this.refresh, LIVE_REFRESH_DELAY_MS);
    },

    // Centrifugo pushes each committed write as a binary protobuf frame. Without it the page only polls.
    async startLive() {
      if (typeof Centrifuge === 'undefined' || typeof AuctionEvent === 'undefined') {
        return;
      }
      const result = await api('realtime');
      if (!result.ok || !result.data) {
        return; // 204: live updates are off on the server
      }

      const url = new URL(result.data.websocketUrl, window.location.href);
      if (url.protocol === 'http:' || url.protocol === 'https:') {
        url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
      }
      this.setLive('connecting');
      const centrifuge = new Centrifuge(url.toString());
      centrifuge.on('connecting', () => this.setLive('connecting'));
      centrifuge.on('disconnected', () => this.setLive('down'));

      const subscription = centrifuge.newSubscription(result.data.channel);
      subscription.on('subscribing', () => this.setLive('connecting'));
      subscription.on('subscribed', (context) => {
        this.setLive('up');
        if (context.wasRecovering && !context.recovered) {
          this.refresh(); // events were missed while disconnected and history no longer has them
        }
      });
      subscription.on('publication', (context) => this.onLiveEvent(context.data));
      subscription.subscribe();
      centrifuge.connect();
      this.centrifuge = centrifuge;
    },

    setLive(state) {
      if (this.live !== state) {
        this.live = state;
        this.schedule(); // the polling interval depends on it
      }
    },

    onLiveEvent(bytes) {
      let event;
      try {
        event = AuctionEvent.decode(bytes);
      } catch (error) {
        console.warn('ignoring an AuctionEvent that does not decode', error);
        return;
      }

      if (event.type === 'CREATED') {
        if (this.foundId === null && this.statusFilter !== 'CLOSED') {
          this.refreshSoon(); // where it lands depends on the page, filter and order, so ask the server
        }
        return;
      }
      if (event.type !== 'BID_PLACED') {
        return;
      }

      const index = this.auctions.findIndex((auction) => auction.id === event.id);
      if (index === -1) {
        return; // not on this page
      }
      const current = this.auctions[index];
      const incoming = AuctionEvent.toAuction(event);
      // Two client pods can publish bids on one auction out of order. Bids only go up, so keep the highest.
      if (this.isClosed(current) || (current.highestBid ?? -1) >= (incoming.highestBid ?? -1)) {
        return;
      }
      const auctions = this.auctions.slice();
      auctions[index] = incoming;
      this.showAuctions(auctions);
    },

    // No event marks an auction closed, because the cluster closes it on its own timer. Ask once it's due.
    refreshEnded() {
      if (this.live !== 'up') {
        return; // polling every 2 seconds already picks it up
      }
      const due = this.auctions.filter((auction) =>
        !this.isClosed(auction) && auction.endsAt <= this.now && !this.endedRefreshed.has(auction.id));
      if (due.length > 0) {
        due.forEach((auction) => this.endedRefreshed.add(auction.id));
        this.refreshSoon();
      }
    },

    onVisibilityChange() {
      if (document.hidden) {
        clearTimeout(this.refreshTimer);
      } else {
        this.refresh();
      }
    },

    async findAuction() {
      if (!isPositiveWhole(this.findQuery)) {
        this.findError = 'Enter a whole number above 0.';
        return;
      }

      const id = Number(this.findQuery);
      this.finding = true;
      const generation = ++this.refreshGeneration; // a list refresh already on its way must not replace the result
      const result = await api(`auctions/${id}`);
      this.finding = false;

      if (result.ok) {
        this.foundId = id;
        this.showAuctions([result.data]);
        this.selectedId = id;
        this.panelTab = 'bid';
        this.findError = '';
        this.connection = 'up';
        window.scrollTo({ top: 0 });
      } else if (result.status === 404) {
        this.findError = `No auction with ID ${id}.`;
      } else {
        const failure = describeFailure(result);
        this.showMessage(failure.text, failure.tone);
      }
      if (generation === this.refreshGeneration) {
        this.schedule();
      }
    },

    clearFind() {
      this.foundId = null;
      this.findQuery = '';
      this.findError = '';
      this.refresh();
    },

    goToPage(page) {
      this.foundId = null; // opening an auction or changing page goes back to the list
      this.findQuery = '';
      this.findError = '';
      this.page = page;
      window.scrollTo({ top: 0 });
      this.refresh();
    },

    setStatusFilter(status) {
      this.statusFilter = status;
      this.goToPage(0);
    },

    validateForm() {
      const errors = {};
      for (const { key } of this.createFields) {
        if (!isPositiveWhole(this.form[key])) {
          errors[key] = 'Enter a whole number above 0.';
        }
      }
      if (!errors.durationSeconds && Number(this.form.durationSeconds) > MAX_DURATION_SECONDS) {
        errors.durationSeconds = `The longest auction is ${MAX_DURATION_SECONDS} seconds (7 days).`;
      }
      this.formErrors = errors;
      return Object.keys(errors).length === 0;
    },

    async createAuction() {
      if (!this.validateForm()) {
        return;
      }
      this.creating = true;
      const result = await api('auctions', {
        sellerId: Number(this.form.sellerId),
        startPrice: Number(this.form.startPrice),
        minIncrement: Number(this.form.minIncrement),
        durationMs: Number(this.form.durationSeconds) * 1000,
      });
      this.creating = false;

      if (result.ok) {
        this.showMessage(`Auction ${result.data.id} is open.`, 'good');
        this.selectedId = result.data.id;
        this.panelTab = 'bid';
        if (this.statusFilter === 'CLOSED') {
          this.statusFilter = 'ALL'; // otherwise the new auction wouldn't be on the list
        }
        // Newest first puts it on the first page. Oldest first puts it last, and refresh corrects a page that's too far.
        this.goToPage(this.order === 'DESC' ? 0 : Math.floor(this.totalItems / this.size));
      } else {
        const failure = describeFailure(result);
        this.showMessage(failure.text, failure.tone);
      }
    },

    validateBid(auction) {
      const draft = this.drafts[auction.id] ?? '';
      if (!this.bidderValid) {
        return 'Enter your user ID first.';
      }
      if (!isPositiveWhole(draft)) {
        return 'Enter a whole number above 0.';
      }
      return Number(draft) < this.minimumBid(auction) ? `Bid at least ${this.minimumBid(auction)}.` : '';
    },

    async placeBid(auction) {
      const error = this.validateBid(auction);
      this.bidErrors[auction.id] = error;
      if (error || this.sending[auction.id]) { // a second click could place the bid twice
        return;
      }

      const id = auction.id;
      const amount = Number(this.drafts[id]);
      this.sending[id] = true;
      const result = await api(`auctions/${id}/bids`, { bidderId: Number(this.bidder), amount });
      this.sending[id] = false;

      if (result.ok) {
        delete this.drafts[id];
        this.showMessage(`Your bid of ${amount} on auction ${id} is in.`, 'good');
      } else {
        const failure = describeFailure(result);
        this.showMessage(`Auction ${id}: ${failure.text}`, failure.tone);
      }
      this.refresh();
    },
  },
}).mount('#app');
