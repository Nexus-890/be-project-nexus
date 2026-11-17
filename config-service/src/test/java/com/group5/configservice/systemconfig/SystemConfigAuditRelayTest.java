package com.group5.configservice.systemconfig;

import com.group5.common_events.EventEnvelope;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SystemConfigAuditRelayTest {
    @TempDir Path directory;

    /** Handoff HTTP lỗi giữ audit trên disk; restart gửi cùng raw/eventId và chỉ xoá sau204. */
    @Test
    void failedHandoffSurvivesRestartAndIsRemovedOnlyAfterAcknowledgement() throws Exception {
        JsonMapper mapper = JsonMapper.shared();
        List<String> raws = new ArrayList<>();
        List<String> signatures = new ArrayList<>();
        AtomicInteger status = new AtomicInteger(503);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/system-config-audit", exchange -> {
            raws.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            signatures.add(exchange.getRequestHeaders().getFirst("X-Config-Audit-Signature"));
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
        Path file = directory.resolve("state.json");
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        String secret = "test-only-config-audit-secret-at-least-32bytes";
        UUID eventId = UUID.randomUUID();
        try {
            try (var store = new SystemConfigStore(file.toString(), mapper)) {
                store.commit("NOTIFICATION_RETRY_LIMIT", 4, new EventEnvelope<>(eventId, "SystemConfigUpdated",
                        "NOTIFICATION_RETRY_LIMIT", Instant.parse("2026-11-14T07:00:00Z"), "trace-config",
                        Map.of("oldValue", 3, "newValue", 4)));
                var relay = new SystemConfigAuditRelay(store, mapper, secret, url);
                relay.relay();
                assertThat(store.pending()).hasSize(1);
                assertThat(signatures.getFirst()).isEqualTo(relay.sign(raws.getFirst()));
            }
            status.set(204);
            try (var restarted = new SystemConfigStore(file.toString(), mapper)) {
                var relay = new SystemConfigAuditRelay(restarted, mapper, secret, url);
                relay.relay();
                assertThat(restarted.pending()).isEmpty();
                assertThat(raws).hasSize(2);
                assertThat(mapper.readTree(raws.get(0)).path("eventId").asText()).isEqualTo(eventId.toString());
                assertThat(mapper.readTree(raws.get(1)).path("eventId").asText()).isEqualTo(eventId.toString());
                assertThat(signatures.get(1)).isEqualTo(relay.sign(raws.get(1)));
            }
        } finally { server.stop(0); }
    }

    /** Secret chưa cấu hình giữ nguyên hàng đợi và không phát sinh network request. */
    @Test
    void missingSecretNeverDiscardsAudit() throws Exception {
        try (var store = new SystemConfigStore(directory.resolve("state.json").toString(), JsonMapper.shared())) {
            store.commit("NOTIFICATION_RETRY_LIMIT", 4, new EventEnvelope<>(UUID.randomUUID(),
                    "SystemConfigUpdated", "NOTIFICATION_RETRY_LIMIT", Instant.now(), "trace-config",
                    Map.of("oldValue", 3, "newValue", 4)));
            new SystemConfigAuditRelay(store, JsonMapper.shared(), "", "http://127.0.0.1:1").relay();
            assertThat(store.pending()).hasSize(1);
        }
    }
}
