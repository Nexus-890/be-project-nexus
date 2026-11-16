package com.group5.auctionservice.stream;

import com.group5.auctionservice.bidding.BidService;
import com.group5.auctionservice.bidding.dto.PlaceBidRequest;
import com.group5.common_security.CurrentUser;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Chạy tải tùy chọn trên local, không thay thế số đo POST hay latency trên STAGING. */
final class AuctionStreamGatewayLoadSupport {
    private final int backendPort;
    private final JdbcTemplate jdbc;
    private final BidService bids;
    private final Tracer tracer;
    private final AuctionStreamProperties properties;

    /** Dùng nguyên pipeline Kafka và hai server đang sống của integration fixture. */
    AuctionStreamGatewayLoadSupport(int backendPort, JdbcTemplate jdbc, BidService bids, Tracer tracer,
                                    AuctionStreamProperties properties) {
        this.backendPort = backendPort;
        this.jdbc = jdbc;
        this.bids = bids;
        this.tracer = tracer;
        this.properties = properties;
    }

    /** Mở 1.000 stream qua gateway, gửi 1 bid/giây trong một phút và lưu p95 đo từ wire data. */
    void run() throws Exception {
        int clients = Integer.getInteger("auction.stream.load.clients", 1_000);
        int bidCount = Integer.getInteger("auction.stream.load.bids", 60);
        Path reportDirectory = Path.of("target", "auction-stream-load").toAbsolutePath();
        Files.createDirectories(reportDirectory);
        Path gatewayJar = Path.of(System.getProperty("auction.stream.gateway-jar",
                "../api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar")).toAbsolutePath().normalize();
        assertThat(gatewayJar).as("Build api-gateway with mvn package before the load run").isRegularFile();
        UUID auction = seed();
        int gatewayPort;
        try (var reservation = new ServerSocket(0)) { gatewayPort = reservation.getLocalPort(); }
        Process gateway = launchGateway(gatewayJar, gatewayPort, reportDirectory.resolve("gateway.log"));
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .executor(executor).connectTimeout(Duration.ofSeconds(30)).build();
        var active = new ConcurrentLinkedQueue<LoadClient>();
        var errors = new ConcurrentLinkedQueue<String>();
        var samples = new ConcurrentLinkedQueue<Sample>();
        var initial = new CountDownLatch(clients);
        var requested = new ConcurrentHashMap<BigDecimal, Long>();
        int previousLimit = properties.getMaxConnections();
        properties.setMaxConnections(Math.max(2_000, clients + 100));
        long started = System.nanoTime();
        try {
            URI state = URI.create("http://127.0.0.1:" + gatewayPort + "/api/v1/auctions/" + auction + "/state");
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(250)).until(() -> {
                if (!gateway.isAlive()) throw new IllegalStateException("Gateway stopped; inspect " + reportDirectory.resolve("gateway.log"));
                try {
                    return http.send(HttpRequest.newBuilder(state).timeout(Duration.ofSeconds(2)).GET().build(),
                            HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
                } catch (Exception notReady) { return false; }
            });
            URI stream = URI.create("http://127.0.0.1:" + gatewayPort + "/api/v1/auctions/" + auction + "/stream");
            var connections = new ArrayList<CompletableFuture<Void>>();
            for (int client = 0; client < clients; client++) {
                int id = client;
                connections.add(http.sendAsync(HttpRequest.newBuilder(stream).header("Accept", "text/event-stream")
                                .GET().build(), HttpResponse.BodyHandlers.ofInputStream())
                        .thenAccept(response -> {
                            if (response.statusCode() != 200) {
                                errors.add("client " + id + " HTTP " + response.statusCode());
                                try { response.body().close(); } catch (Exception ignored) { }
                                return;
                            }
                            active.add(new LoadClient(id, response.body(), initial, samples, errors, bidCount));
                        }));
                // Ramp-up tránh đo backlog TCP của máy local; sau ramp vẫn giữ đủ 1.000 socket đồng thời.
                if ((client + 1) % 50 == 0) {
                    CompletableFuture.allOf(connections.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
                    connections.clear();
                }
            }
            CompletableFuture.allOf(connections.toArray(CompletableFuture[]::new)).get(90, TimeUnit.SECONDS);
            assertThat(initial.await(30, TimeUnit.SECONDS)).as("Every client receives the initial state").isTrue();
            assertThat(errors).isEmpty();
            assertThat(active).hasSize(clients);
            var bidder = new CurrentUser(UUID.randomUUID(), Set.of("BUYER"), Set.of("AUCTION.BID"), "Load Buyer");
            long biddingStarted = System.nanoTime();
            for (int index = 0; index < bidCount; index++) {
                long remaining = biddingStarted + index * TimeUnit.SECONDS.toNanos(1) - System.nanoTime();
                if (remaining > 0) LockSupport.parkNanos(remaining);
                BigDecimal amount = BigDecimal.valueOf(110L + index * 10L).stripTrailingZeros();
                requested.put(amount, System.nanoTime());
                Span span = tracer.nextSpan().name("auction.stream-load.bid").start();
                try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                    bids.place(auction, UUID.randomUUID().toString(), new PlaceBidRequest(amount), bidder);
                } finally { span.end(); }
            }
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(samples).as("All %s clients receive all %s bid changes", clients, bidCount).hasSize(clients * bidCount));
            assertThat(errors).isEmpty();
            long minuteRemaining = biddingStarted + TimeUnit.SECONDS.toNanos(bidCount) - System.nanoTime();
            if (minuteRemaining > 0) LockSupport.parkNanos(minuteRemaining);
            Map<BigDecimal, Instant> eventTimes = new HashMap<>();
            jdbc.query("SELECT payload::jsonb #>> '{payload,amount}', occurred_at FROM outbox WHERE aggregate_id = ? AND event_type = 'BidPlaced'",
                    row -> { eventTimes.put(new BigDecimal(row.getString(1)).stripTrailingZeros(), row.getTimestamp(2).toInstant()); },
                    auction.toString());
            assertThat(eventTimes).hasSize(bidCount);
            List<Double> eventLatencies = samples.stream().map(sample ->
                    Duration.between(eventTimes.get(sample.amount()), sample.receivedTime()).toNanos() / 1_000_000.0).sorted().toList();
            List<Double> queueLatencies = samples.stream().map(sample ->
                    (sample.receivedNano() - requested.get(sample.amount())) / 1_000_000.0).sorted().toList();
            double p95 = percentile(eventLatencies, .95);
            var report = new java.util.LinkedHashMap<String, Object>();
            report.put("environment", "LOCAL Windows, Docker PostgreSQL18, "
                    + System.getProperty("auction.test.kafka-image", "apache/kafka:4.3.1")
                    + ", packaged WebFlux gateway; not STAGING");
            report.put("kafkaImage", System.getProperty("auction.test.kafka-image", "apache/kafka:4.3.1"));
            report.put("javaVersion", System.getProperty("java.version"));
            report.put("jvmActiveProcessors", Runtime.getRuntime().availableProcessors());
            report.put("testJvmMaxHeapMiB", Runtime.getRuntime().maxMemory() / (1024 * 1024));
            report.put("gatewayJvmMaxHeapMiB", 384);
            report.put("connectionRampBatch", 50);
            report.put("bidDriver", "Direct production BidService.place with tracing; POST HTTP latency not measured");
            report.put("latencyBoundary", "BidPlaced outbox occurred_at to viewer SSE wire receipt, all bid/viewer pairs");
            report.put("clients", clients);
            report.put("bids", bidCount);
            report.put("bidIntervalMillis", 1_000);
            report.put("receivedStateChanges", samples.size());
            report.put("errors", errors.size());
            report.put("bidPlacedToViewerP95Millis", p95);
            report.put("bidPlacedToViewerMaxMillis", eventLatencies.getLast());
            report.put("acceptToViewerP95Millis", percentile(queueLatencies, .95));
            report.put("acceptToViewerMaxMillis", queueLatencies.getLast());
            report.put("totalElapsedSeconds", (System.nanoTime() - started) / 1_000_000_000.0);
            report.put("completedAt", Instant.now().toString());
            Files.writeString(reportDirectory.resolve("result.json"), JsonMapper.shared().writerWithDefaultPrettyPrinter().writeValueAsString(report));
            System.out.println("SSE_GATEWAY_LOAD " + JsonMapper.shared().writeValueAsString(report));
            assertThat(p95).as("BidPlaced -> 1,000 gateway viewers p95 in milliseconds").isLessThan(1_000);
        } finally {
            active.forEach(LoadClient::close);
            http.shutdownNow();
            executor.shutdownNow();
            properties.setMaxConnections(previousLimit);
            gateway.destroy();
            if (!gateway.waitFor(5, TimeUnit.SECONDS)) gateway.destroyForcibly();
        }
    }

    /** Chạy artifact gateway thật, dùng discovery tĩnh tới auction instance và giữ nguyên route SSE. */
    private Process launchGateway(Path jar, int port, Path log) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Path config = Path.of("../config-service/src/main/resources/config/api-gateway.yml").toAbsolutePath().normalize();
        return new ProcessBuilder(java, "-Xms32m", "-Xmx384m", "-XX:ActiveProcessorCount=2", "-Duser.timezone=UTC",
                "-jar", jar.toString(), "--server.port=" + port, "--server.shutdown=immediate",
                "--spring.config.location=optional:classpath:/auction-stream-gateway-load.properties",
                "--spring.config.import=" + config.toUri(),
                "--spring.cloud.config.enabled=false", "--spring.cloud.config.import-check.enabled=false",
                "--spring.cloud.compatibility-verifier.enabled=false", "--eureka.client.enabled=false",
                "--spring.cloud.discovery.client.simple.instances.auction-service[0].uri=http://127.0.0.1:" + backendPort,
                "--management.tracing.export.zipkin.enabled=false",
                "--nexus.security.jwt.issuer=http://localhost:18080/realms/nexus",
                "--nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/certs")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    /** Deadline dài tránh dừng stream giữa bài đo; bid và anti-sniping đã có bài chức năng riêng. */
    private UUID seed() {
        return jdbc.queryForObject("""
                INSERT INTO auctions (product_id, sku_id, seller_id, title, starting_price, current_price,
                    bid_increment, start_at, end_at, original_end_at, status, visibility)
                VALUES (?, ?, ?, 'SSE gateway load fixture', 100, 100, 10, now() - interval '1 hour',
                    now() + interval '1 hour', now() + interval '1 hour', 'ACTIVE', 'PUBLIC') RETURNING id
                """, UUID.class, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    /** Dùng nearest-rank p95 trên mọi cặp bid/viewer, không lấy trung bình của từng client. */
    private static double percentile(List<Double> sorted, double fraction) {
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * fraction) - 1));
    }

    private record Sample(int client, BigDecimal amount, long receivedNano, Instant receivedTime) { }

    /** Mỗi client có một virtual reader và chỉ giữ mẫu đo, không giữ toàn bộ DTO 60.000 thông điệp. */
    private static final class LoadClient {
        private final InputStream body;
        private final Thread reader;
        private volatile boolean closing;

        /** Đọc framing SSE như EventSource; xem EOF hoặc trùng version là lỗi khi bài đo còn đang chạy. */
        private LoadClient(int client, InputStream body, CountDownLatch initial,
                           ConcurrentLinkedQueue<Sample> samples, ConcurrentLinkedQueue<String> errors, int expected) {
            this.body = body;
            reader = Thread.ofVirtual().start(() -> {
                int changes = 0;
                try (var lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                    String event = "";
                    StringBuilder data = new StringBuilder();
                    String line;
                    while ((line = lines.readLine()) != null) {
                        if (line.isEmpty()) {
                            if ("state".equals(event)) {
                                var state = JsonMapper.shared().readTree(data.toString());
                                int count = state.path("bidCount").asInt();
                                if (count == 0) initial.countDown();
                                else {
                                    if (count != changes + 1) errors.add("client " + client + " bidCount sequence " + changes + " -> " + count);
                                    changes = count;
                                    samples.add(new Sample(client, state.path("currentPrice").decimalValue().stripTrailingZeros(),
                                            System.nanoTime(), Instant.now()));
                                }
                            }
                            event = ""; data.setLength(0);
                        } else if (line.startsWith("event:")) event = line.substring(6).stripLeading();
                        else if (line.startsWith("data:")) {
                            if (!data.isEmpty()) data.append('\n');
                            data.append(line.substring(5).stripLeading());
                        }
                    }
                    if (!closing && changes < expected) errors.add("client " + client + " EOF after " + changes + " bids");
                } catch (Exception failure) {
                    if (!closing) errors.add("client " + client + " " + failure);
                }
            });
        }

        /** Đóng socket chủ động sau đo, không tính việc cleanup là lỗi truyền tải. */
        private void close() {
            closing = true;
            try { body.close(); } catch (Exception ignored) { }
            reader.interrupt();
        }
    }
}