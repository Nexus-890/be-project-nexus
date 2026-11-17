package com.group5.notificationservice.delivery;

import com.group5.notificationservice.shared.config.DeliveryProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Kiểm tra ConfigData reload qua actuator HTTP thật và retry bean hiện có, không restart context. */
@SpringBootTest(classes = NotificationRuntimeRefreshHttpTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:file:${nexus.retry.test-config}",
        "spring.application.name=notification-service",
        "spring.cloud.config.enabled=false",
        "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.refresh.additional-property-sources-to-retain=Inlined Test Properties,Dynamic Test Properties",
        "spring.cloud.discovery.enabled=false",
        "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "management.endpoints.web.exposure.include=health,refresh",
        "management.tracing.export.enabled=false"
})
class NotificationRuntimeRefreshHttpTest {
    private static final Path CONFIG = createConfig();
    private static final String ADMIN = "ADMIN~SYSTEM.CONFIG.UPDATE";
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort private int port;
    @Autowired private RetryPolicy retries;
    @Autowired private ConfigurableApplicationContext context;

    /** Giới hạn mới được áp dụng bằng HTTP refresh, giữ nguyên worker bean và ngày khởi động. */
    @Test
    void nextRetryUsesUpdatedSourceAfterAuthenticatedHttpRefreshWithoutRestart() throws Exception {
        Instant failedAt = Instant.parse("2026-11-14T07:00:00Z");
        RetryPolicy original = retries;
        long started = context.getStartupDate();
        assertThat(retries.limit()).isEqualTo(3);
        assertThat(retries.nextAttemptAt(3, true, failedAt)).isEmpty();

        writeLimit(4);
        assertThat(retries.limit()).isEqualTo(3);
        assertThat(refresh(ADMIN).statusCode()).isEqualTo(200);

        assertThat(context.getBean(RetryPolicy.class)).isSameAs(original);
        assertThat(context.getStartupDate()).isEqualTo(started);
        assertThat(retries.limit()).isEqualTo(4);
        assertThat(retries.nextAttemptAt(3, true, failedAt)).isPresent();

        writeLimit(2);
        assertThat(refresh(ADMIN).statusCode()).isEqualTo(200);
        assertThat(retries.limit()).isEqualTo(2);
        assertThat(retries.nextAttemptAt(2, true, failedAt)).isEmpty();
        assertThat(context.getBean(RetryPolicy.class)).isSameAs(original);
        writeLimit(3);
        assertThat(refresh(ADMIN).statusCode()).isEqualTo(200);
    }

    /** Anonymous, Support và Admin thiếu privilege không thể áp dụng nguồn mới qua actuator. */
    @Test
    void refreshAuthorizationProtectsTheRealActuatorEndpoint() throws Exception {
        int original = retries.limit();
        writeLimit(original + 1);
        try {
            assertThat(refresh(null).statusCode()).isEqualTo(401);
            assertThat(refresh("SUPPORT_STAFF~SYSTEM.CONFIG.UPDATE").statusCode()).isEqualTo(403);
            assertThat(refresh("BUYER~SYSTEM.CONFIG.UPDATE").statusCode()).isEqualTo(403);
            assertThat(refresh("ADMIN~SYSTEM.CONFIG.VIEW").statusCode()).isEqualTo(403);
            assertThat(retries.limit()).isEqualTo(original);
        } finally {
            writeLimit(original);
        }
    }

    /** Gọi actuator HTTP của Spring Cloud thật; không thay ContextRefresher hoặc RefreshScope bằng mock. */
    private HttpResponse<String> refresh(String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/refresh"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** File cấu hình là nguồn ConfigData kiểm soát được, không đụng file deployment thật. */
    private static Path createConfig() {
        try {
            Path file = Files.createTempFile("notification-retry-http-", ".properties");
            Files.writeString(file, "nexus.notification.delivery.retry-limit=3\n");
            System.setProperty("nexus.retry.test-config", file.toString());
            return file;
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }

    /** Đổi nguồn trên đĩa để ContextRefresher phải đọc lại cấu hình thay vì sửa trực tiếp bean. */
    private static void writeLimit(int value) throws IOException {
        Files.writeString(CONFIG, "nexus.notification.delivery.retry-limit=" + value + "\n");
    }

    /** Dọn đúng file tạm của fixture sau khi đã kết thúc các lần refresh. */
    @AfterAll
    static void cleanup() throws IOException {
        Files.deleteIfExists(CONFIG);
        System.clearProperty("nexus.retry.test-config");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            DataJpaRepositoriesAutoConfiguration.class, FlywayAutoConfiguration.class, KafkaAutoConfiguration.class,
            com.group5.common_events.autoconfigure.EventsAutoConfiguration.class,
            com.group5.common_outbox.autoconfigure.IdempotentConsumerAutoConfiguration.class})
    @Import({DeliveryProperties.class, RetryPolicy.class})
    static class TestApplication {
        /** Chỉ chữ ký JWT là fixture; filter, claim conversion và role/privilege checks đều chạy thật. */
        @Bean
        JwtDecoder decoder() {
            return token -> {
                String[] fields = token.split("~", 2);
                if (fields.length != 2) throw new BadJwtException("Invalid fixture JWT");
                return Jwt.withTokenValue(token).header("alg", "RS256")
                        .subject("00000000-0000-4000-8000-000000000060")
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                        .claim("roles", List.of(fields[0])).claim("privileges", List.of(fields[1])).build();
            };
        }
    }
}
