# Aeron Auction: Plan

A simple auction system: a Spring Boot REST API sends commands to a 3-node Aeron Cluster, which holds all
auction state. An Agrona `DeadlineTimerWheel` handles timeouts, and Prometheus and Grafana show metrics.

The aim is a small, readable reference project, so simple code wins over peak throughput.

## 1. Architecture

```
 HTTP                                        Aeron UDP
────────►  auction-client  ── ingress ───►  ┌────────────────────────────┐
           (Spring Boot)   ◄── egress ────  │ node0  LEADER              │
           one agent thread                 │ node1  FOLLOWER   Raft log │
           owns the session                 │ node2  FOLLOWER            │
                 ▲                          └─────────────▲──────────────┘
                 │ /actuator/prometheus                   │ /actuator/prometheus
                 └──────────── Prometheus ────────────────┘
                                   │
                                Grafana
```

### Key decisions

| Topic | Decision | Why |
|---|---|---|
| RPC transport | Use the `AeronCluster` client (ingress and egress) | It already runs over Aeron transport, and it adds what raw pub/sub lacks: commands reach the replicated log, and the client follows a new leader |
| Modules | `auction-client`, `auction-cluster`, and a shared `auction-protocol` | The client and cluster only share messages, so the client carries no cluster code. Neither of them depends on the other |
| Encoding | SBE (Simple Binary Encoding), generated at build time from `sbe/auction-schema.xml` | One schema defines all messages and snapshot records. Generated code only needs Agrona, which `aeron-all` bundles |
| Auction storage | `ArrayList` indexed by `id - 1` | IDs are sequential and auctions are never removed. A rough benchmark at 1M auctions: bid lookup + update took 40ns, against 155ns for `LinkedHashMap` and 1,188ns for `TreeMap`, at about half the memory per auction. Cleaning up closed auctions would require changing this |
| Code style | Agrona's Checkstyle rules, pinned to commit `4e0400c5` and run on every build | A violation fails the build, so the style cannot drift |
| Dev mode | A `dev` Spring profile on both apps runs one node on this machine. `deploy/docker-compose.dev.yml` activates it in Docker and reuses the 3-node service definitions, with its own data | Faster and lighter for daily work, but it has no failover. Without the profile, settings are required, so a real deployment cannot fall back to local defaults |
| Web UI | Vue 3 in the client's `static/` folder, using its browser build from the `org.webjars.npm:vue` Maven dependency | No Node build step or CDN, and it ships in the same jar. Inputs are plain text, so there are no number spinners. It refreshes every 2 seconds while its tab is visible, and each refresh is a read through the log |
| API docs | springdoc 3.2.0 serves Swagger UI and the OpenAPI document from `auction-client` | Standard for Spring Boot. Response types and example values are declared on the controller, because they cannot be inferred from `ResponseEntity<?>` |
| Testing | No unit tests. Verify by running the real stack and breaking it (section 9) | Project decision |
| Reads | `GET` also goes through the log | Linearizable and needs no second code path, at the cost of read latency |
| Timeouts | Agrona wheel in both modules (see section 5) | Auction expiry in the cluster, RPC timeout in the client |

### Versions

| Component | Version | Notes |
|---|---|---|
| Java | 25 | Needs `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED` everywhere (see section 10) |
| Aeron | `io.aeron:aeron-all:1.54.0` | Already bundles Agrona, so do not add Agrona separately |
| Spring Boot | 4.1.2 | Use `spring-boot-starter-webmvc` and `spring-boot-starter-actuator` |
| Micrometer | 1.17.2 | Managed by the Boot BOM. Add `micrometer-registry-prometheus` |
| SBE | `uk.co.real-logic:sbe-tool:1.41.0` | Build time only |
| Checkstyle | 10.23.0 | The version Agrona used at the pinned commit |
| Maven wrapper | 3.9.16 | Do not move to 3.10.0: IntelliJ 2026.1 fails to sync with it |

## 2. Project layout

```
aeron-auction/
├── pom.xml                       packaging=pom, keeps the Boot parent, lists both modules
├── auction-client/               Spring Boot app (existing main class moves here)
│   └── gc/garcol/aeronauction/client/
│       ├── AeronAuctionApplication.java
│       ├── api/AuctionController.java      REST + DTOs + status mapping
│       └── rpc/ClusterRpcAgent.java        session, pending map, RPC timeout wheel
├── auction-cluster/              Spring Boot app, one per node
│   └── gc/garcol/aeronauction/cluster/
│       ├── AuctionClusterApplication.java, AuctionNodeConfig.java   Boot entry point and wiring
│       ├── AuctionNode.java      driver + archive + consensus + service
│       ├── AuctionService.java   ClusteredService: dispatch, snapshot, tick
│       ├── AuctionBook.java      pure business logic, no Aeron dependency
│       ├── AuctionTimers.java    wheel wrapper (section 5)
│       └── NodeMetrics.java      business metrics + Aeron counters
├── auction-protocol/             shared by the client and the cluster
│   ├── gc/garcol/aeronauction/protocol/    domain records + Codec, which wraps the generated SBE classes
│   └── src/main/resources/sbe/auction-schema.xml
├── config/checkstyle/            Agrona's rules
└── deploy/
    ├── docker-compose.yml        node0-2, client, prometheus, grafana
    ├── prometheus.yml
    ├── grafana/                  provisioned datasource + dashboard JSON
    └── demo.sh                   one auction through the REST API
```

## 3. REST API

Amounts are `long` minor units, so there is no floating point in replicated state.

| Method | Path | Body | Success |
|---|---|---|---|
| `POST` | `/auctions` | `sellerId, startPrice, minIncrement, durationMs` | `201` + auction |
| `POST` | `/auctions/{id}/bids` | `bidderId, amount` | `200` + auction |
| `GET` | `/auctions/{id}` | none | `200` + auction |
| `GET` | `/auctions?page=0&size=20` | none | `200` + `items, page, size, totalItems, totalPages` |

Auction response: `id, sellerId, startPrice, minIncrement, highestBid, highestBidderId, endsAt, status`.
`status` is `OPEN` or `CLOSED`. The winner is `highestBidderId` once the auction is closed.

| Situation | HTTP |
|---|---|
| Invalid input, such as a duration outside 1s to 7d | `400` |
| Unknown auction | `404` |
| Auction closed, bid too low, or seller bidding on their own auction | `409` |
| Client queue full, cluster back-pressured, or no session | `503` |
| No reply within the RPC timeout | `504` |

`504` means **outcome unknown**, because the command may still commit. The client never retries on its own,
since that could place a bid twice. The response body says to check with `GET`.

## 4. Protocol

Defined in `auction-protocol/src/main/resources/sbe/auction-schema.xml`. Every message starts with SBE's standard
header, and every command and reply carries a `correlationId` that the reply echoes.

| Message | Fields |
|---|---|
| `CreateAuction` | `correlationId, sellerId, startPrice, minIncrement, durationMs` |
| `PlaceBid` | `correlationId, auctionId, bidderId, amount` |
| `GetAuction` | `correlationId, auctionId` |
| `ListAuctions` | `correlationId, page, size` |
| `AuctionReply` | `correlationId, status, auction` |
| `AuctionPageReply` | `correlationId, status, page, size, totalItems`, and a repeating group of `auction` |
| `SnapshotHeader` | `nextAuctionId, lastTimestamp` |
| `SnapshotAuction` | `auction` |

`Codec` translates between these and the domain records. It holds reusable SBE flyweights, so it is not thread-safe
and each owning thread has its own instance. It rejects messages with a wrong schema ID or a too-short length, and
the cluster drops those rather than crashing.

## 5. Timeouts with `DeadlineTimerWheel`

### In the cluster: auction expiry

The wheel lives inside the replicated state machine, so it must behave identically on all 3 nodes.

The wheel cannot wake itself up, so one cluster timer (`TICK_ID`) drives it:

- **Arm:** schedule `TICK_ID` when the first open auction appears.
- **Each tick:** drain the wheel with the event's timestamp, close what expired, then re-arm for `timestamp + 128ms`,
  or disarm if no timers remain.
- **Cost:** about 8 log entries per second, only while auctions are open. Auctions close up to about 128ms after
  `endsAt` (plus up to one wheel tick), which is acceptable because bids are rejected at the exact deadline regardless.

```java
// Tick resolution and ticks per wheel must both be powers of 2. This wheel spans about 65s per revolution,
// and longer deadlines are fine because they simply wait in their spoke.
static final long RESOLUTION_MS = 128;
static final int TICKS_PER_WHEEL = 512;

void schedule(long auctionId, long endsAt, long timestamp) {
    if (wheel.timerCount() == 0) wheel.resetStartTime(timestamp);   // the wheel may have gone stale
    timerToAuction.put(wheel.scheduleTimer(endsAt), auctionId);
}

void advance(long now, LongConsumer closeAuction) {
    expired.clear();
    while (wheel.currentTickTime() <= now) {            // one poll advances at most one tick
        wheel.poll(now, this::collectExpired, Integer.MAX_VALUE);
    }
    wheel.poll(now, this::collectExpired, Integer.MAX_VALUE);   // required: visits the tick containing now
    expired.sort();                                     // firing order is not a stable contract
    expired.forEach(closeAuction);
}
```

**Rules that keep replicas identical.** Nothing automated checks these, so keep them in mind when changing this code:

1. **Always finish the drain with the extra `poll`.** It makes the wheel fire exactly the timers with
   `deadline <= now`, whatever its internal tick. Without it, replicas diverge. See section 10 for the evidence.
2. **Only use time from the log:** the `timestamp` passed to `onSessionMessage` and `onTimerEvent`.
   Never use `System.currentTimeMillis()`, randomness, or I/O in state logic.
3. **Sort expired auction IDs before closing them.** Timer IDs and firing order can differ after a rebuild.
4. **Never snapshot the wheel.** Timer IDs are internal, so rebuild the wheel from the open auctions.
5. **A bid's validity depends only on `timestamp >= endsAt`,** never on whether the tick has run yet. A `GET` in that
   gap also reports `CLOSED`.
6. **Don't touch the wheel in `onStart` using `cluster.time()`.** It is `0` there on a fresh start, and `timeUnit()` is
   `null`. Create the wheel lazily or from the snapshot's saved timestamp.

**Why not one cluster timer per auction?** `cluster.scheduleTimer(auctionId, endsAt)` would be simpler and needs none
of the rules above. The wheel was requested, and its real benefit is that many deadlines share one replicated timer,
so scheduling adds no extra log traffic.

### In the client: RPC timeout

This wheel is ordinary local code on the agent thread, so it uses the real clock and needs no determinism rules.
Each request schedules a 2s timer. On expiry, the matching future fails with `504`. A reply that arrives first cancels
the timer, and a reply that arrives after expiry is dropped.

## 6. Cluster node

`AuctionService implements ClusteredService`, with logic kept in the Aeron-free `AuctionBook`.

| Callback | Behaviour |
|---|---|
| `onStart` | Load the snapshot if there is one, then rebuild the wheel |
| `onSessionMessage` | Decode, apply to `AuctionBook`, schedule the timer on create, reply to the session |
| `onTimerEvent` | Run the tick described in section 5 |
| `onTakeSnapshot` | Write a header (`nextAuctionId`, `lastTimestamp`), then every auction |
| `onRoleChange` | Record the role for metrics only |

- **Replies:** call `session.offer` on every node. Followers get a mocked success, so no role check is needed.
  Retry back-pressure with `cluster.idleStrategy().idle()`, and do the same for `scheduleTimer`.
- **Snapshot restore:** rebuild the wheel from `lastTimestamp` and re-add open auctions. Treat `TICK_ID` as
  armed if any auction is open, because cluster timers are stored by the consensus module.
- **Closed auctions:** kept in memory forever. That is fine for this project.
- **Process:** `AuctionNode` launches `ClusteredMediaDriver` and `ClusteredServiceContainer` in one JVM. It uses
  `ThreadingMode.SHARED` and backoff idle strategies so 3 nodes don't saturate a laptop.
- **Configuration:** environment variables `NODE_ID`, `CLUSTER_MEMBERS`, `CLUSTER_DIR`, `AERON_DIR`, all required unless
  the `dev` Spring profile is active.
- **Lifecycle:** Spring Boot starts and closes the node. A failed start exits with status 1 so Docker can retry, and
  `ClusterTool shutdown` stops the application with status 0.

Member string, using the same ports in each container:

```
0,node0:9002,node0:9003,node0:9004,node0:9005,node0:8010|1,node1:9002,...|2,node2:9002,...
  ingress    consensus  log        catchup    archive
```

## 7. Client

`AeronCluster` is not thread-safe, so a single `ClusterRpcAgent` thread owns it and all RPC state.

```
HTTP thread: encode → enqueue → return CompletableFuture     (queue full → 503)
Agent loop:  drain queue → offer → pollEgress → poll wheel → sendKeepAlive when due
```

- **Queue:** `ManyToOneConcurrentArrayQueue`, capacity 4096.
- **Pending requests:** a map of `correlationId` to future and timer ID, touched only by the agent thread.
- **Back-pressure:** if `offer` returns a negative value, fail the request with `503`. Don't block the loop.
- **Failover:** `AeronCluster` follows a new leader by itself. If the session closes, fail all pending requests with
  `503` and reconnect. `/actuator/health` reports down while disconnected.
- **Ingress endpoints:** `0=node0:9002,1=node1:9002,2=node2:9002`.

## 8. Metrics, Prometheus and Grafana

**Client** (`/actuator/prometheus`): the built-in `http_server_requests` metrics, plus:

- `auction_rpc_duration` (timer, by message type and outcome)
- `auction_rpc_pending` (gauge)
- `auction_rpc_timeouts_total`
- `auction_rpc_rejected_total` (by reason)
- `auction_client_reconnects_total`

**Cluster node** (`:9100/actuator/prometheus`): the standard Spring Boot metrics, plus:

| Metric | Source |
|---|---|
| `auction_auctions_opened_total`, `auction_auctions_closed_total` | Service |
| `auction_bids_total{result}` | Service |
| `auction_close_lag_ms` (close time minus `endsAt`) | Service |
| `auction_open_auctions`, `auction_wheel_timers` | Service |
| `aeron_cluster_role` (type 201), `aeron_cluster_commit_position` (203) | Aeron counters |
| `aeron_cluster_election_state` (207), `aeron_cluster_leadership_term` (239) | Aeron counters |
| `aeron_cluster_errors` (212), `aeron_cluster_client_timeouts` (213) | Aeron counters |
| `aeron_cluster_service_max_cycle_ns` (218) | Aeron counters |
| `aeron_driver_errors`, `aeron_driver_naks_received`, `aeron_driver_short_sends` | Driver system counters |

**Metrics must never affect state.** The service only writes to them, and scrapes read on the metrics thread.
A restarted node replays its log, which would count everything twice, so business **counters** increment only when
`cluster.role() == LEADER`. Gauges reflect current state on every node.

**Grafana dashboard** (provisioned from the repo, so it works on first start):

- **Cluster health:** role per node, leader changes, commit position per node, errors
- **Business:** open auctions, auction and bid rates, rejected bids by result, close lag
- **API:** request rate, p50/p99 latency, RPC timeouts, pending requests, reconnects

## 9. Milestones

Each milestone ended with the real system doing the listed thing, or, for milestone 1, a passing build.

| # | Work | Done when |
|---|---|---|
| 1 | Multi-module build, SBE generation, Checkstyle | `./mvnw clean package` passes with 0 violations |
| 2 | Protocol and `AuctionBook` | Bid rules behave correctly through the REST API |
| 3 | `AuctionTimers` | Auctions close on time on all nodes |
| 4 | `AuctionService`, snapshot and restore | State and open auctions survive a full restart from a snapshot |
| 5 | `AuctionNode`, 3 nodes | After killing the leader, a new one is elected and the auction closes once, with the same winner on the surviving nodes |
| 6 | `ClusterRpcAgent` and REST API | The full flow works over HTTP, plus the `504` and `503` paths |
| 7 | Docker Compose | `docker compose up` then `demo.sh` works, and killed nodes rejoin |
| 8 | Metrics, Prometheus, Grafana | All scrape targets are up and every dashboard panel returns data |
| 9 | README and demo script | A new reader can go from clone to a closed auction using the README alone |

**Docker notes:**

- Run the client inside Compose. Aeron UDP between macOS and containers is unreliable, so all Aeron traffic stays on
  the Compose network, and only `8080`, `9090` and `3000` are published.
- Set `shm_size` for the Aeron directory, because the default 64MB is too small for Aeron's log buffers. Start with
  `1g`, and enable sparse term buffers.
- Give each node its own volume for its cluster and archive directories, so state survives restarts.
- Base image: `eclipse-temurin:25-jre`.
- Nodes use `restart: on-failure`. After a hard kill, Aeron refuses to start until the old mark files have gone stale,
  so a node exits and retries. This took about 21 seconds when measured.

## 10. What has been verified

These were tested on 2026-11-01 with throwaway programs against `aeron-all-1.54.0` on GraalVM 25.0.1.

| Finding | Evidence | Effect on the plan |
|---|---|---|
| Aeron fails on Java 25 without the flag | `IllegalAccessError` on `jdk.internal.misc.Unsafe`. Works with `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED` | Add the flag to Surefire `argLine`, Docker, `spring-boot:run` and IntelliJ run configs |
| A single-node cluster works | Session message, reply and cluster timer all worked | The API usage in this plan is sound |
| A plain drain loop makes replicas diverge | Simulated restores landing between ticks, 200 runs: 114,826 mismatched events, or 920 with start times aligned to the tick. With the extra `poll`: **0**, aligned or not, and the fired set always equals `deadline <= now` | Rule 1 in section 5 |
| One `poll` advances at most one tick | A timer 5s overdue took 8 polls to fire | Loop in section 5 |
| Timer resolution must be a power of 2 | `100` throws `tick resolution must be a power of 2` | Use 128ms |
| Timers beyond one revolution are safe | A 600s deadline on a 65s wheel fired at the right time, not early | No wheel size limit on auction duration |
| Firing order is not insertion order | A later-scheduled timer fired first | Rule 3 in section 5 |
| `cluster.time()` is unusable in `onStart` | Returned `0`, and `timeUnit()` was `null`, on a fresh start | Rule 6 in section 5 |

**Verified on the running 3-node Docker stack** on 2026-11-02, using the final code:

| Scenario | Result |
|---|---|
| Normal auction | Correct `201`, `200`, `400`, `404` and `409` responses, and the same close on all 3 nodes |
| Kill the leader mid-auction | A bid was accepted again about 5 seconds later, and the auction closed once |
| Restart the killed node | It rejoined and replayed to the same closes |
| Kill both followers | Bid returned `504` |
| Kill all 3 nodes | Requests returned `503`, and health reported `DOWN` |
| Restart all 3 nodes | Service recovered from the log, and the client reconnected on its own |
| Snapshot, then kill and restart all 3 nodes | Open auction restored, IDs continued, a new bid was accepted, and the auction closed on all nodes |
| Prometheus and Grafana | All 4 targets up, and all 16 dashboard panels return data |

The tick timer surviving a snapshot restore is confirmed by the last auction closing after the restart. `/actuator/prometheus`
on Boot 4 works with the actuator starter plus `micrometer-registry-prometheus`, and `shm_size: 1g` is enough.

**Bugs this process found:**

- Micrometer's Prometheus naming strips a `_created` suffix, so that counter is now named `opened`.
- An early `AuctionTimers` called on a never-used wheel looped for billions of ticks. `advance` now returns at once if
  the wheel is empty.
- A node that failed at startup hung instead of exiting, so it could never retry. It now exits non-zero.

**Limit of this approach:** with no automated tests, nothing re-checks these scenarios after a change. In particular,
the rules in section 5 are only protected by code review, so rerun these scenarios after changing the timer or snapshot
code.

## 11. Out of scope

Authentication, persistence beyond Aeron's log and snapshots, auction cancellation, anti-sniping extensions, push
notifications to bidders, cleanup of closed auctions, TLS, and production tuning such as dedicated threads and
kernel-bypass networking.

## 12. Implementation progress

- [x] 1. Multi-module build, SBE generation, Checkstyle
- [x] 2. Protocol and `AuctionBook`
- [x] 3. `AuctionTimers`
- [x] 4. `AuctionService`, snapshot and restore
- [x] 5. `AuctionNode` and 3-node failover
- [x] 6. `ClusterRpcAgent` and REST API
- [x] 7. Docker Compose
- [x] 8. Metrics, Prometheus, Grafana
- [x] 9. README and demo script
