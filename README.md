# Aeron Auction

An auction system with a REST API in front of a 3-node [Aeron Cluster](https://aeron.io). The cluster holds all state,
so an auction keeps running and closes with the same winner even if the leader dies. See [plan.md](plan.md) for the
design.

| Module             | What it is                                                               |
|--------------------|--------------------------------------------------------------------------|
| `auction-client`   | Spring Boot REST API that forwards each request to the cluster           |
| `auction-cluster`  | Spring Boot app that runs one cluster node: the replicated state machine |
| `auction-protocol` | The SBE schema and message code that the other two share                 |

Messages use SBE. Auction deadlines and RPC timeouts use Agrona's `DeadlineTimerWheel`.

## Run it

You need Java 25 and Docker.

```bash
./mvnw clean package
docker compose -f deploy/docker-compose.yml up -d --build
./deploy/demo.sh
```

`demo.sh` runs one auction: it creates it, places bids, waits for the deadline, and reads the winner.

| URL                                   | What                                                                        |
|---------------------------------------|-----------------------------------------------------------------------------|
| http://localhost:8080                 | Web UI, built with Vue, to open and find auctions, bid and watch them close |
| http://localhost:8080/auctions        | REST API                                                                    |
| http://localhost:8080/swagger-ui.html | Swagger UI, to read and try the API                                         |
| http://localhost:8080/v3/api-docs     | OpenAPI document                                                            |
| http://localhost:3000                 | Grafana, which opens on the Aeron Auction dashboard                         |
| http://localhost:9090                 | Prometheus                                                                  |

Stop with `docker compose -f deploy/docker-compose.yml down`. Add `-v` to delete the cluster's data as well.

## Dev mode: one node

Both apps have a `dev` Spring profile that runs a one-node cluster with no other setup. A single node is quicker to
start
and uses less memory, but it has no failover, so use the 3-node setup to try that.

Without the `dev` profile, the apps need their settings from the environment, and they stop at startup if one is
missing.
The Compose files set them for you.

**On your machine or in IntelliJ**, for debugging. Start the node first:

```bash
./mvnw clean package
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -jar auction-cluster/target/auction-cluster-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -jar auction-client/target/auction-client-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
./deploy/demo.sh
```

In IntelliJ, choose **aeron-auction (dev)** from the run configurations and press Run or Debug. It starts both apps with
the JVM flag Aeron needs. These configurations are in `.run/`. Stop the Docker stacks first, because the client also
uses
port 8080.

A run configuration that IntelliJ generated, such as from the green arrow next to `main`, has no profile and no JVM
flag,
so it won't start. Delete those configurations, or set their active profile to `dev` and add the flag to their VM
options.

Data goes under the system temp directory, in `auction-dev`, and survives restarts. Prometheus and Grafana are not
included in this mode, but metrics are at http://localhost:9100/actuator/prometheus and
http://localhost:8080/actuator/prometheus.

**In Docker**, with the same client, Prometheus and Grafana on the same ports. Stop the 3-node stack first, because the
ports clash. Its data is kept separately.

```bash
./mvnw clean package
docker compose -f deploy/docker-compose.dev.yml up -d --build
./deploy/demo.sh
docker compose -f deploy/docker-compose.dev.yml down   # add -v to delete the data
```

## API

Amounts are whole numbers in minor units.

```bash
curl -X POST localhost:8080/auctions -H 'Content-Type: application/json' \
  -d '{"sellerId":1,"startPrice":100,"minIncrement":10,"durationMs":60000}'

curl -X POST localhost:8080/auctions/1/bids -H 'Content-Type: application/json' \
  -d '{"bidderId":2,"amount":100}'

curl localhost:8080/auctions/1

curl 'localhost:8080/auctions?page=0&size=20&status=OPEN&order=DESC'
```

Listing returns auctions newest first, as `items` with `page`, `size`, `totalItems` and `totalPages`. `page` starts at 0
and defaults to 0, and `size` defaults to 20 with a maximum of 100. A page past the last one is empty.

`status` is `ALL` (the default), `OPEN` or `CLOSED`, and `totalItems` and `totalPages` count only the auctions that match.
`order` sorts by ID and is `DESC` (the default) or `ASC`. With `DESC`, a new auction moves later items onto later pages.

| Status | Meaning                                                                                       |
|--------|-----------------------------------------------------------------------------------------------|
| `400`  | Invalid input                                                                                 |
| `404`  | Unknown auction                                                                               |
| `409`  | Auction closed, bid too low, or the seller bidding on their own auction                       |
| `503`  | The command was not sent, so it did not happen                                                |
| `504`  | No reply in time, so **the command may still have applied**. Check with `GET` before retrying |

## Benchmark

With [k6](https://k6.io) installed, set `DOMAIN` to the API's address:

```bash
K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_OPEN=true K6_WEB_DASHBOARD_EXPORT=benchmark/report.html \
  DOMAIN=localhost:8080 k6 run benchmark/list-auctions.js
```

This opens a live dashboard at http://localhost:5665 and writes the results to `benchmark/report.html` when the run
ends,
replacing any earlier report. The dashboard closes when k6 exits, so use the report after that. Add `VUS=100`,
`DURATION=5m` or `SIZE=100` to change the load.

The run fails if more than 1% of requests fail or the p95 latency is over 500ms. Listing goes through the replicated
log,
so every request adds to the log on disk. Create some auctions first, or the pages will be empty.

## Try breaking it

```bash
cd deploy

# Kill the leader. Find it on the Grafana "Node role" panel. Requests fail for a few seconds, then recover.
docker compose kill node0
docker compose start node0   # it rejoins and catches up, after about 20 seconds

# Take a snapshot. Run this on the current leader.
docker compose exec node0 java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED -cp tools/aeron-all.jar \
  io.aeron.cluster.ClusterTool /data/cluster snapshot
```

## Development

- **Code style:** Agrona's Checkstyle rules, in `config/checkstyle`. Every build runs them and fails on a violation.
- **Messages:** edit `auction-protocol/src/main/resources/sbe/auction-schema.xml`. The build regenerates the codecs.
- **Running outside Docker:** add `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED` to the JVM options, because
  Aeron fails on Java 25 without it.
- **IntelliJ:** keep the Maven wrapper on 3.9.x. IntelliJ 2026.1 fails to sync with Maven 3.10.0.
