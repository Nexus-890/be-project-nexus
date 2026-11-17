package com.group5.configservice.systemconfig;

import com.group5.common_events.EventEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemConfigStoreTest {
    @TempDir Path directory;

    /** Khởi động lại vẫn có giá trị số và đúng eventId audit chưa được ACK. */
    @Test
    void valueAndPendingAuditSurviveRestartAndAckIsDurable() throws Exception {
        Path file = directory.resolve("state.json");
        EventEnvelope<?> event = event();
        try (var store = new SystemConfigStore(file.toString(), JsonMapper.shared())) {
            store.commit("NOTIFICATION_RETRY_LIMIT", 4, event);
            assertThat(store.values()).containsEntry("NOTIFICATION_RETRY_LIMIT", 4);
        }
        try (var restarted = new SystemConfigStore(file.toString(), JsonMapper.shared())) {
            assertThat(restarted.values()).containsEntry("NOTIFICATION_RETRY_LIMIT", 4);
            assertThat(restarted.pending()).singleElement().satisfies(pending -> {
                assertThat(pending.eventId()).isEqualTo(event.eventId());
                assertThat(pending.payload()).isInstanceOf(Map.class);
            });
            restarted.acknowledge(event.eventId());
        }
        try (var restarted = new SystemConfigStore(file.toString(), JsonMapper.shared())) {
            assertThat(restarted.values()).containsEntry("NOTIFICATION_RETRY_LIMIT", 4);
            assertThat(restarted.pending()).isEmpty();
        }
    }

    /** Cùng một state-file chỉ có một writer; không mất audit vì last-write-wins. */
    @Test
    void secondWriterCannotOpenTheSameState() throws Exception {
        Path file = directory.resolve("state.json");
        try (var first = new SystemConfigStore(file.toString(), JsonMapper.shared())) {
            assertThatThrownBy(() -> new SystemConfigStore(file.toString(), JsonMapper.shared()))
                    .isInstanceOf(OverlappingFileLockException.class);
        }
    }

    /** Crash giữa stage và commit được roll-forward đúng một lần; không mất actor/audit. */
    @Test
    void stagedIntentIsRecoveredOnRestartWithTheSameEventId() throws Exception {
        Path file = directory.resolve("state.json");
        EventEnvelope<?> event = event();
        try (var first = new SystemConfigStore(file.toString(), JsonMapper.shared())) {
            first.stage("NOTIFICATION_RETRY_LIMIT", 4, event);
            assertThat(first.values()).isEmpty();
            assertThat(first.pending()).isEmpty();
        }
        try (var recovered = new SystemConfigStore(file.toString(), JsonMapper.shared())) {
            assertThat(recovered.values()).containsEntry("NOTIFICATION_RETRY_LIMIT", 4);
            assertThat(recovered.pending()).singleElement().satisfies(row ->
                    assertThat(row.eventId()).isEqualTo(event.eventId()));
        }
        try (var restartedAgain = new SystemConfigStore(file.toString(), JsonMapper.shared())) {
            assertThat(restartedAgain.pending()).hasSize(1);
        }
    }

    /** Lỗi có kiểm soát hủy stage nên restart không hồi sinh một cập nhật đã từ chối. */
    @Test
    void abortedIntentDoesNotApplyAfterRestart() throws Exception {
        Path file = directory.resolve("state.json");
        try (var first = new SystemConfigStore(file.toString(), JsonMapper.shared())) {
            first.stage("NOTIFICATION_RETRY_LIMIT", 4, event());
            first.abort();
        }
        try (var restarted = new SystemConfigStore(file.toString(), JsonMapper.shared())) {
            assertThat(restarted.values()).isEmpty();
            assertThat(restarted.pending()).isEmpty();
        }
    }

    /** Event fixture có actor và old/new đúng kiểu JSON để kiểm tra restart. */
    private EventEnvelope<?> event() {
        return new EventEnvelope<>(UUID.randomUUID(), "SystemConfigUpdated", "NOTIFICATION_RETRY_LIMIT",
                Instant.parse("2026-11-14T07:00:00Z"), "trace-config", Map.of("oldValue", 3, "newValue", 4));
    }
}
