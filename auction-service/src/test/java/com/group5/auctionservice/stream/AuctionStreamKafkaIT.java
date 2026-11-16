package com.group5.auctionservice.stream;

import com.group5.auctionservice.AuctionServiceApplication;
import com.group5.auctionservice.TestPostgres;
import com.group5.auctionservice.bidding.BidService;
import com.group5.auctionservice.bidding.dto.PlaceBidRequest;
import com.group5.common_events.Topics;
import com.group5.common_events.EventKafka;
import com.group5.common_security.CurrentUser;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Hai instance HTTP thật cùng DB và Kafka Testcontainer: kiểm chứng fan-out và deadline anti-sniping. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/auction-stream-test.properties",
        "spring.config.import=file:../config-service/src/main/resources/config/auction-service.yml",
        "server.shutdown=immediate", "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "spring.jpa.open-in-view=false",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.kafka.consumer.group-id=auction-stream-test",
        "spring.kafka.consumer.auto-offset-reset=earliest", "spring.kafka.consumer.enable-auto-commit=false",
        "spring.kafka.listener.ack-mode=record", "management.tracing.enabled=true", "management.tracing.export.zipkin.enabled=false",
        "nexus.auction.scheduler.enabled=false", "nexus.outbox.relay.enabled=true",
        "nexus.outbox.relay.fixed-delay=50", "nexus.outbox.relay.initial-delay=50",
        "nexus.auction.stream.max-connections=1", "nexus.auction.stream.group-id=auction-stream-it-first",
        "nexus.security.jwt.issuer=http://localhost:18080/realms/nexus",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/certs"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuctionStreamKafkaIT {
    private static final TestPostgres POSTGRES = new TestPostgres();
    private static final KafkaContainer KAFKA = new KafkaContainer(
            System.getProperty("auction.test.kafka-image", "apache/kafka:4.3.1"))
            .withStartupTimeout(Duration.ofSeconds(90));
    static {
        POSTGRES.start();
        KAFKA.start();
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(Topics.AUCTION, 1, (short) 1),
                    new NewTopic(Topics.AUCTION_BIDS, 1, (short) 1),
                    new NewTopic(Topics.INVENTORY, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
        } catch (Exception failure) { throw new IllegalStateException("Cannot create auction test topics", failure); }
    }

    @Autowired private org.springframework.context.ApplicationContext application;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BidService bids;
    @Autowired private Tracer tracer;
    @Autowired private AuctionStreamProperties streamProperties;
    @Autowired private KafkaListenerEndpointRegistry listeners;
    @LocalServerPort private int firstPort;
    private ConfigurableApplicationContext second;
    private int secondPort;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Kafka và PostgreSQL dùng cổng container thật, không có broker hay state query giả. */
    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    /** Instance thứ hai cùng group bid-command nhưng có group SSE khác để nhận đầy đủ mọi bid. */
    @BeforeAll
    void startSecondInstance() {
        second = new SpringApplicationBuilder(AuctionServiceApplication.class).run(
                "--server.port=0", "--server.shutdown=immediate",
                "--spring.config.location=optional:classpath:/auction-stream-test.properties",
                "--spring.config.import=file:../config-service/src/main/resources/config/auction-service.yml",
                "--spring.cloud.config.enabled=false", "--spring.cloud.config.import-check.enabled=false",
                "--spring.cloud.discovery.enabled=false", "--eureka.client.enabled=false",
                "--spring.cloud.compatibility-verifier.enabled=false", "--spring.jpa.open-in-view=false",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--spring.kafka.consumer.group-id=auction-stream-test",
                "--spring.kafka.consumer.auto-offset-reset=earliest",
                "--spring.kafka.consumer.enable-auto-commit=false", "--spring.kafka.listener.ack-mode=record",
                "--management.tracing.enabled=true", "--management.tracing.export.zipkin.enabled=false", "--nexus.auction.scheduler.enabled=false",
                "--nexus.outbox.relay.enabled=true", "--nexus.outbox.relay.fixed-delay=50",
                "--nexus.outbox.relay.initial-delay=50", "--nexus.auction.stream.max-connections=1",
                "--nexus.auction.stream.group-id=auction-stream-it-second",
                "--nexus.security.jwt.issuer=http://localhost:18080/realms/nexus",
                "--nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/certs");
        secondPort = ((ServletWebServerApplicationContext) second).getWebServer().getPort();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(listeners.getListenerContainer("auction-stream").getAssignedPartitions()).hasSize(1);
            var replica = second.getBean(KafkaListenerEndpointRegistry.class);
            assertThat(replica.getListenerContainer("auction-stream").getAssignedPartitions()).hasSize(1);
            // Chờ cả hai thành viên bid-group hoàn tất rebalance trước khi đo steady-state latency.
            var commands = java.util.stream.Stream.concat(listeners.getListenerContainers().stream(),
                            replica.getListenerContainers().stream())
                    .filter(container -> "auction-bid-commands".equals(container.getGroupId())).toList();
            assertThat(commands).hasSize(2);
            assertThat(commands).allSatisfy(container -> assertThat(container.getAssignedPartitions()).isNotNull());
            assertThat(commands.stream().mapToInt(container -> container.getAssignedPartitions().size()).sum()).isEqualTo(1);
        });
        // Warm metadata của cả hai relay; bài đo không gộp thời gian khởi tạo broker/client vào độ trễ event.
        for (var application : List.of(second)) {
            var producer = application.getBean(EventKafka.TEMPLATE, KafkaTemplate.class);
            producer.partitionsFor(Topics.AUCTION);
            producer.partitionsFor(Topics.AUCTION_BIDS);
        }
        var producer = (KafkaTemplate<?, ?>) application.getBean(EventKafka.TEMPLATE);
        producer.partitionsFor(Topics.AUCTION);
        producer.partitionsFor(Topics.AUCTION_BIDS);
    }

    /** Dừng replica và HttpClient kể cả khi assertion latency thất bại. */
    @AfterAll
    void stopSecondInstance() {
        if (second != null) second.close();
        http.close();
    }

    /** Bid thật qua queue tới hai stream dưới một giây, kèm endAt mới và không lộ bidderId. */
    @Test
    void queuedBidReachesBothInstancesWithinOneSecondWithExtendedDeadline() throws Exception {
        UUID auction = seed("PUBLIC");
        UUID restricted = seed("RESTRICTED");
        try (var first = connect(firstPort, auction); var replica = connect(secondPort, auction)) {
            var firstInitial = first.state(Duration.ofSeconds(2));
            var secondInitial = replica.state(Duration.ofSeconds(2));
            assertThat(firstInitial.data().path("currentPrice").decimalValue()).isEqualByComparingTo("100");
            assertThat(secondInitial.id()).isEqualTo(firstInitial.id());
            Instant oldDeadline = OffsetDateTime.parse(firstInitial.data().path("endAt").asString()).toInstant();
            assertThat(firstInitial.data().has("code")).isFalse();
            assertThat(firstInitial.data().has("leaderBidderId")).isFalse();

            // Giới hạn là per-instance; mỗi replica đã giữ một viewer nhưng đều được phục vụ bình thường.
            assertProblem(firstPort, auction, 503, "AUCTION_STREAM_BUSY");
            assertProblem(firstPort, restricted, 404, "AUCTION_NOT_FOUND");
            assertProblem(firstPort, UUID.randomUUID(), 404, "AUCTION_NOT_FOUND");

            var bidder = new CurrentUser(UUID.randomUUID(), Set.of("BUYER"), Set.of("AUCTION.BID"), "Nguyen An");
            long requested = System.nanoTime();
            BidService.PlacementResult receipt;
            // Mô phỏng scope tracing của HTTP request thật khi gọi accept trực tiếp trong fixture.
            Span span = tracer.nextSpan().name("auction.stream-test.bid").start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                receipt = bids.place(auction, UUID.randomUUID().toString(),
                        new PlaceBidRequest(new BigDecimal("120")), bidder);
            } finally { span.end(); }
            var firstBid = first.state(Duration.ofSeconds(2));
            var replicaBid = replica.state(Duration.ofSeconds(2));
            assertThat(Duration.ofNanos(firstBid.receivedAt() - requested)).isLessThan(Duration.ofSeconds(2));
            assertThat(Duration.ofNanos(replicaBid.receivedAt() - requested)).isLessThan(Duration.ofSeconds(2));
            // AC SSE tính từ BidPlaced đã tạo; AC queue tính từ lúc POST accept, không gộp sai hai ngân sách.
            Instant eventAt = jdbc.queryForObject("SELECT occurred_at FROM outbox WHERE aggregate_id = ? AND event_type = 'BidPlaced'",
                    (row, index) -> row.getTimestamp(1).toInstant(), auction.toString());
            assertThat(Duration.between(eventAt, firstBid.receivedTime())).isLessThan(Duration.ofSeconds(1));
            assertThat(Duration.between(eventAt, replicaBid.receivedTime())).isLessThan(Duration.ofSeconds(1));
            for (var message : List.of(firstBid, replicaBid)) {
                assertThat(message.data().path("currentPrice").decimalValue()).isEqualByComparingTo("120");
                assertThat(message.data().path("bidCount").asInt()).isEqualTo(1);
                assertThat(message.data().path("leaderBidderName").asString()).isEqualTo("N***n");
                assertThat(OffsetDateTime.parse(message.data().path("endAt").asString()).toInstant())
                        .isEqualTo(oldDeadline.plusSeconds(300));
                assertThat(Long.parseLong(message.id())).isGreaterThan(Long.parseLong(firstInitial.id()));
                assertThat(message.data().has("leaderBidderId")).isFalse();
            }
            assertThat(firstBid.data().path("endAt")).isEqualTo(replicaBid.data().path("endAt"));
            assertThat(jdbc.queryForObject("SELECT status::text FROM bids WHERE id = ?", String.class,
                    receipt.response().bidId())).isEqualTo("PLACED");
            System.out.printf("SSE two-instance accept-to-wire: first=%.1f ms, replica=%.1f ms%n",
                    (firstBid.receivedAt() - requested) / 1_000_000.0, (replicaBid.receivedAt() - requested) / 1_000_000.0);
            System.out.printf("SSE two-instance BidPlaced-to-wire: first=%d ms, replica=%d ms%n",
                    Duration.between(eventAt, firstBid.receivedTime()).toMillis(),
                    Duration.between(eventAt, replicaBid.receivedTime()).toMillis());
        }
        // Bài tải local chỉ chạy khi được gọi rõ ràng; không tăng một phút cho mọi lần verify thông thường.
        if (Boolean.getBoolean("auction.stream.load")) {
            new AuctionStreamGatewayLoadSupport(firstPort, jdbc, bids, tracer, streamProperties).run();
        }
    }

    /** Tạo auction sắp hết giờ để bid qua consumer thật kích hoạt gia hạn anti-sniping năm phút. */
    private UUID seed(String visibility) {
        return jdbc.queryForObject("""
                INSERT INTO auctions (product_id, sku_id, seller_id, title, starting_price, current_price,
                    bid_increment, start_at, end_at, original_end_at, status, visibility)
                VALUES (?, ?, ?, 'SSE Kafka fixture', 100, 100, 10, now() - interval '1 hour',
                    now() + interval '30 seconds', now() + interval '30 seconds', 'ACTIVE', ?::auction_visibility)
                RETURNING id
                """, UUID.class, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), visibility);
    }

    /** Mở HTTP stream thật, kiểm tra header chống buffer trước khi đọc state. */
    private StreamClient connect(int port, UUID auction) throws Exception {
        var response = http.send(HttpRequest.newBuilder(uri(port, auction)).header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        assertThat(response.headers().firstValue("X-Accel-Buffering")).contains("no");
        return new StreamClient(response.body());
    }

    /** Lỗi phải có Problem Details trước khi mở stream, không thành body event-stream lỗi. */
    private void assertProblem(int port, UUID auction, int status, String code) throws Exception {
        var response = http.send(HttpRequest.newBuilder(uri(port, auction)).header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(JsonMapper.shared().readTree(response.body()).path("code").asString()).isEqualTo(code);
    }

    /** Dùng cùng URL công khai mà EventSource của trình duyệt gọi. */
    private URI uri(int port, UUID auction) {
        return URI.create("http://127.0.0.1:" + port + "/api/v1/auctions/" + auction + "/stream");
    }

    private record StateMessage(String id, JsonNode data, long receivedAt, Instant receivedTime) { }

    /** Reader riêng ghi thời điểm nhận wire data; thời gian chờ assertion không làm sai số latency. */
    private static final class StreamClient implements AutoCloseable {
        private final InputStream body;
        private final BlockingQueue<StateMessage> states = new LinkedBlockingQueue<>();
        private final Thread reader;
        private volatile Throwable failure;

        /** Đọc từng dòng theo framing SSE chuẩn, không phụ thuộc MockMvc hay emitter giả. */
        private StreamClient(InputStream body) {
            this.body = body;
            reader = Thread.ofVirtual().start(() -> {
                try (var lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                    String event = "";
                    String id = "";
                    StringBuilder data = new StringBuilder();
                    String line;
                    while ((line = lines.readLine()) != null) {
                        if (line.isEmpty()) {
                            if ("state".equals(event)) states.add(new StateMessage(id,
                                    JsonMapper.shared().readTree(data.toString()), System.nanoTime(), Instant.now()));
                            event = ""; id = ""; data.setLength(0);
                        } else if (line.startsWith("event:")) event = line.substring(6).stripLeading();
                        else if (line.startsWith("id:")) id = line.substring(3).stripLeading();
                        else if (line.startsWith("data:")) {
                            if (!data.isEmpty()) data.append('\n');
                            data.append(line.substring(5).stripLeading());
                        }
                    }
                } catch (Exception exception) { failure = exception; }
            });
        }

        /** Chỉ lấy event state, heartbeat không được làm tăng số lần thay đổi giá. */
        private StateMessage state(Duration timeout) throws InterruptedException {
            StateMessage result = states.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(result).as("SSE state; reader failure=%s", failure).isNotNull();
            return result;
        }

        /** Đóng socket để servlet gọi cleanup và không giữ tài nguyên giữa các fixture. */
        @Override public void close() throws Exception { body.close(); reader.interrupt(); }
    }
}