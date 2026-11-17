package com.group5.configservice.systemconfig;

import com.group5.common_events.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.time.Duration;

/** Retry bền vững: recorder lỗi không làm mất SystemConfigUpdated đã được chấp nhận. */
@Component
@ConditionalOnProperty(prefix = "nexus.system-config.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SystemConfigAuditRelay {
    private static final Logger log = LoggerFactory.getLogger(SystemConfigAuditRelay.class);
    private final SystemConfigStore store;
    private final JsonMapper mapper;
    private final String secret;
    private final String url;
    private final RestClient client;

    /** Dùng chữ ký riêng cho handoff nội bộ, không lưu JWT của người thay đổi cấu hình. */
    public SystemConfigAuditRelay(SystemConfigStore store, JsonMapper mapper,
            @Value("${nexus.system-config.audit.shared-secret:}") String secret,
            @Value("${nexus.system-config.notification-url:http://localhost:8086}") String url) {
        this.store = store; this.mapper = mapper; this.secret = secret; this.url = url;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    /** Chỉ bỏ audit khỏi file sau khi recorder xác nhận đã commit, retry giữ nguyên eventId. */
    @Scheduled(fixedDelayString = "${nexus.system-config.audit.interval-ms:2000}")
    public synchronized void relay() {
        for (EventEnvelope<?> event : store.pending()) {
            try {
                String raw = mapper.writeValueAsString(event);
                var response = client.post().uri(url + "/internal/v1/system-config-audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Config-Audit-Signature", sign(raw))
                        .body(raw).retrieve().toBodilessEntity();
                if (response.getStatusCode().value() != 204) {
                    throw new IllegalStateException("Audit recorder did not acknowledge the event");
                }
                store.acknowledge(event.eventId());
            } catch (Exception failure) {
                log.warn("System configuration audit delivery postponed eventId={}", event.eventId(), failure);
                return;
            }
        }
    }

    /** Ký đúng byte JSON; secret thiếu hoặc quá ngắn làm handoff dừng an toàn và giữ audit. */
    String sign(String raw) throws Exception {
        if (secret.length() < 32) throw new IllegalStateException("Config audit shared secret is required");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
