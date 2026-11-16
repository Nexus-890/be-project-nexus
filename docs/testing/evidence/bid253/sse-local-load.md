# Local SSE load evidence

Measured on 16 November 2026 in the primary local project. This is local evidence, not a STAGING measurement.

- Infrastructure: Docker PostgreSQL 18 and `apache/kafka:4.0.0`; two real auction-service HTTP instances and the packaged WebFlux api-gateway. Java 21.0.12.1; test JVM 512 MiB, gateway JVM 384 MiB, each `-XX:ActiveProcessorCount=2`.
- Opened 1,000 concurrent SSE connections through the gateway, ramping up in batches of 50 connections. Connections remained open for a one-minute bidding window.
- Submitted 60 real bid requests at one-second intervals using the production `BidService.place` with an active trace. The real outbox relay, Kafka command consumer, conditional PostgreSQL update, event relay and SSE consumer delivered the results.
- All 60,000 expected state changes arrived in sequence, with zero client errors. BidPlaced-to-viewer p95: **318.5368 ms**; maximum: **380.8335 ms**. Accept-to-viewer p95: **426.7788 ms**; maximum: **489.1788 ms**.
- SSE latency starts at the `BidPlaced` outbox event `occurred_at` and ends when each client reads the SSE state from the gateway socket. Percentiles include every bid/viewer pair.
- The separate two-instance check delivered the same bid and extended `endAt` to viewers on both replicas in **293 ms** from BidPlaced. Both streams received the current state immediately. Unknown/restricted auctions returned 404 and an exhausted connection limit returned 503 Problem Details before opening SSE.

Run from `be-project-nexus/auction-service`, after packaging api-gateway:

```powershell
.\mvnw.cmd -Dtest=AuctionStreamKafkaIT -Dauction.test.kafka-image=apache/kafka:4.0.0 -Dauction.stream.load=true test
```

The load driver calls the production acceptance service directly. It does **not** measure HTTP POST p95, authentication overhead, STAGING performance, or the 50 ms POST criterion. Those remain separate STAGING acceptance checks. Kafka 4.0.0 was selected from the cached images after an earlier local 4.3.1 container startup timeout; the default fixture image remains the project version 4.3.1.

Machine logs are intentionally excluded from Git. The structured measurements are in `sse-local-load.json`.