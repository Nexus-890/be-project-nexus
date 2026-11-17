package com.group5.notificationservice.eventlog;

import com.group5.common_core.exception.DomainException;
import com.group5.notificationservice.shared.exception.NotificationErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Handoff nội bộ có chữ ký riêng; không mở endpoint này qua gateway. */
@RestController
public class SystemConfigAuditController {
    private final EventLogRecorder recorder;
    private final JsonMapper mapper;
    private final String secret;

    /** Nhận recorder dùng chung để audit và dedup vẫn nằm trong notification_db. */
    public SystemConfigAuditController(EventLogRecorder recorder, JsonMapper mapper,
            @Value("${nexus.system-config.audit.shared-secret:}") String secret) {
        this.recorder = recorder; this.mapper = mapper; this.secret = secret;
    }

    /** Xác thực đúng byte và loại sự kiện trước khi ghi; chỉ ACK sau transaction của recorder. */
    @PostMapping("/internal/v1/system-config-audit")
    public ResponseEntity<Void> record(@RequestBody String raw,
            @RequestHeader(value = "X-Config-Audit-Signature", required = false) String signature) {
        if (raw.length() > 65536) throw new DomainException(NotificationErrorCode.DATA_INVALID);
        authenticate(raw, signature);
        var event = mapper.readTree(raw);
        if (!"SystemConfigUpdated".equals(event.path("eventType").asText())) {
            throw new DomainException(NotificationErrorCode.DATA_INVALID);
        }
        recorder.recordRaw(raw, null);
        return ResponseEntity.noContent().build();
    }

    /** So sánh HMAC theo thời gian hằng; secret rỗng không trở thành chế độ công khai. */
    private void authenticate(String raw, String signature) {
        try {
            if (secret.length() < 32 || signature == null) throw new IllegalArgumentException();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            if (!MessageDigest.isEqual(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)),
                    HexFormat.of().parseHex(signature))) throw new IllegalArgumentException();
        } catch (Exception failure) {
            throw new DomainException(NotificationErrorCode.AUDIT_SIGNATURE_INVALID);
        }
    }
}
