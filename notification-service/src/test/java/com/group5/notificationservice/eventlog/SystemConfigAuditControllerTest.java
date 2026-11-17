package com.group5.notificationservice.eventlog;

import com.group5.common_core.exception.DomainException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SystemConfigAuditControllerTest {
    private static final String SECRET = "test-only-system-config-audit-secret-32bytes";
    private static final String RAW = "{\"eventType\":\"SystemConfigUpdated\",\"payload\":{\"oldValue\":3,\"newValue\":4}}";
    private final EventLogRecorder recorder = mock(EventLogRecorder.class);
    private final SystemConfigAuditController controller = new SystemConfigAuditController(recorder,
            JsonMapper.shared(), SECRET);

    /** Chữ ký hợp lệ chỉ nhận ACK204 sau khi recorder lưu raw envelope không qua Kafka. */
    @Test
    void signedAuditIsAcknowledgedAfterDurableRecorderReturns() throws Exception {
        assertThat(controller.record(RAW, sign(RAW)).getStatusCode().value()).isEqualTo(204);
        verify(recorder).recordRaw(RAW, null);
    }

    /** Recorder DB lỗi thì không ACK để relay giữ eventId và retry về sau. */
    @Test
    void recorderFailureIsPropagatedWithoutAcknowledgement() throws Exception {
        doThrow(new IllegalStateException("db unavailable")).when(recorder).recordRaw(RAW, null);
        String signature = sign(RAW);
        assertThatThrownBy(() -> controller.record(RAW, signature)).isInstanceOf(IllegalStateException.class);
    }

    /** Không có secret, chữ ký sai hoặc sửa byte JSON đều không ghi audit. */
    @Test
    void invalidOrMissingSignatureNeverReachesRecorder() throws Exception {
        assertThatThrownBy(() -> controller.record(RAW, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> controller.record(RAW, "malformed")).isInstanceOf(DomainException.class);
        String original = sign(RAW);
        assertThatThrownBy(() -> controller.record(RAW + " ", original)).isInstanceOf(DomainException.class);
        var unconfigured = new SystemConfigAuditController(recorder, JsonMapper.shared(), "");
        assertThatThrownBy(() -> unconfigured.record(RAW, original)).isInstanceOf(DomainException.class);
        verifyNoInteractions(recorder);
    }

    /** HMAC này chỉ cấp quyền ghi đúng SystemConfigUpdated, không cho giả UserDeleted. */
    @Test
    void otherEventTypesAreRejectedEvenWithValidSignature() throws Exception {
        String raw = "{\"eventType\":\"UserDeleted\"}";
        String signature = sign(raw);
        assertThatThrownBy(() -> controller.record(raw, signature)).isInstanceOf(DomainException.class);
        verifyNoInteractions(recorder);
    }

    /** Ký fixture độc lập để kiểm tra hai phía thống nhất đúng byte và thuật toán. */
    private String sign(String raw) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
