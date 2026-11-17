package com.group5.configservice.systemconfig;

import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.environment.PropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SystemConfigServiceTest {
    @TempDir Path directory;
    private final RuntimeEnvironmentRepository repository = mock(RuntimeEnvironmentRepository.class);
    private final SystemConfigRefresher refresher = mock(SystemConfigRefresher.class);
    private final EventEnvelopeFactory events = mock(EventEnvelopeFactory.class);
    private final KeycloakRealmConfigClient keycloak = mock(KeycloakRealmConfigClient.class);
    private final UUID actor = UUID.randomUUID();

    /** Fixture đọc giá trị thực tế mới hơn SRS mà không phụ thuộc env máy chạy test. */
    @BeforeEach
    void setup() {
        when(keycloak.currentValues()).thenReturn(Map.of("SESSION_TIMEOUT_MINUTES", 60,
                "MAX_LOGIN_ATTEMPTS", 7, "PASSWORD_POLICY", "length(12)", "ACCESS_TOKEN_EXPIRY_MINUTES", 5));
        when(repository.baseline(anyString())).thenAnswer(call -> {
            Environment environment = new Environment(call.getArgument(0), "default");
            environment.add(new PropertySource("deployed", Map.of("nexus.payment.provider", "SEPAY",
                    "nexus.iam.default-user-role", "BUYER", "nexus.notification.delivery.retry-limit", 3)));
            return environment;
        });
        when(events.createWithTimestamp(anyString(), anyString(), any())).thenAnswer(call -> {
            Instant now = Instant.parse("2026-11-14T07:00:00Z");
            Function<Instant, ?> payload = call.getArgument(2);
            return new EventEnvelope<>(UUID.randomUUID(), call.getArgument(0), call.getArgument(1), now,
                    "trace-config", payload.apply(now));
        });
    }

    /** Danh sách có57key và phân biệt source SEPAY/BUYER với default STRIPE/USER trong SRS. */
    @Test
    void listShowsCurrentValuesAndOriginalDefaults() throws Exception {
        try (var store = store()) {
            var service = new SystemConfigService(store, repository, refresher, events, keycloak);
            assertThat(service.list()).hasSize(57).filteredOn(row -> row.key().equals("PAYMENT_PROVIDER"))
                    .singleElement().satisfies(row -> {
                        assertThat(row.value()).isEqualTo("SEPAY");
                        assertThat(row.defaultValue()).isEqualTo("STRIPE");
                    });
        }
    }

    /** Thành công ghi nguyên tử value/audit sau refresh và bảo toàn actor, old/new. */
    @Test
    void updatePersistsValueAndTypedAuditOnlyAfterOwnerRefresh() throws Exception {
        try (var store = store()) {
            var service = new SystemConfigService(store, repository, refresher, events, keycloak);
            doAnswer(call -> {
                assertThat(store.values()).isEmpty();
                assertThat(store.pending()).isEmpty();
                return null;
            }).when(refresher).refresh("notification-service", "admin-token");
            assertThat(service.update("NOTIFICATION_RETRY_LIMIT", 4, actor, "admin-token").value()).isEqualTo(4);
            assertThat(store.values()).containsEntry("NOTIFICATION_RETRY_LIMIT", 4);
            var payload = (SystemConfigService.ConfigUpdated) store.pending().getFirst().payload();
            assertThat(payload.oldValue()).isEqualTo(3);
            assertThat(payload.newValue()).isEqualTo(4);
            assertThat(payload.actorId()).isEqualTo(actor);
            verify(repository).tentative("NOTIFICATION_RETRY_LIMIT", 4);
            verify(repository).clearTentative();
        }
    }

    /** Service owner lỗi giữ nguyên file/audit và yêu cầu refresh lại nguồn cũ. */
    @Test
    void refreshFailureLeavesNoValueOrAuditAndAttemptsCompensation() throws Exception {
        try (var store = store()) {
            var service = new SystemConfigService(store, repository, refresher, events, keycloak);
            doThrow(new IllegalStateException("owner down")).doNothing()
                    .when(refresher).refresh("notification-service", "admin-token");
            assertThatThrownBy(() -> service.update("NOTIFICATION_RETRY_LIMIT", 4, actor, "admin-token"))
                    .isInstanceOf(DomainException.class).extracting("errorCode").isEqualTo("CONFIG_UPDATE_FAILED");
            assertThat(store.values()).isEmpty();
            assertThat(store.pending()).isEmpty();
            verify(refresher, times(2)).refresh("notification-service", "admin-token");
        }
    }

    /** Sai kiểu hoặc key lạ bị từ chối trước mọi network call và audit. */
    @Test
    void invalidRequestsNeverRefreshOrWrite() throws Exception {
        try (var store = store()) {
            var service = new SystemConfigService(store, repository, refresher, events, keycloak);
            assertThatThrownBy(() -> service.update("NOTIFICATION_RETRY_LIMIT", "4", actor, "token"))
                    .isInstanceOf(DomainException.class);
            assertThatThrownBy(() -> service.update("DB_PASSWORD", "secret", actor, "token"))
                    .isInstanceOf(DomainException.class);
            verifyNoInteractions(refresher, events);
            assertThat(store.pending()).isEmpty();
        }
    }

    /** Gửi lại cùng giá trị không tạo thêm audit hoặc refresh vô ích. */
    @Test
    void unchangedValueDoesNotCreateEvent() throws Exception {
        try (var store = store()) {
            var service = new SystemConfigService(store, repository, refresher, events, keycloak);
            service.update("NOTIFICATION_RETRY_LIMIT", 3, actor, "token");
            verifyNoInteractions(refresher, events);
            assertThat(store.pending()).isEmpty();
        }
    }

    /** Không trình bày default SRS thành cấu hình Keycloak thực và không nhận PUT giả thành công. */
    @Test
    void keycloakManagedSettingsUseRealmValuesAndCannotBeUpdated() throws Exception {
        try (var store = store()) {
            var service = new SystemConfigService(store, repository, refresher, events, keycloak);
            assertThat(service.list()).filteredOn(row -> row.key().equals("MAX_LOGIN_ATTEMPTS"))
                    .singleElement().satisfies(row -> {
                        assertThat(row.value()).isEqualTo(7);
                        assertThat(row.configuredValue()).isEqualTo(5);
                        assertThat(row.applyMode()).isEqualTo("KEYCLOAK_MANAGED");
                    });
            assertThatThrownBy(() -> service.update("MAX_LOGIN_ATTEMPTS", 6, actor, "token"))
                    .isInstanceOf(DomainException.class).extracting("errorCode")
                    .isEqualTo("CONFIG_KEY_EXTERNALLY_MANAGED");
            verifyNoInteractions(refresher, events);
        }
    }

    /** Không cho cấu hình provider/role đã retired hoặc khoảng thời gian auction đảo ngược. */
    @Test
    void unsupportedConsumerSettingsFailBeforeRemoteRefresh() throws Exception {
        try (var store = store()) {
            var service = new SystemConfigService(store, repository, refresher, events, keycloak);
            assertThatThrownBy(() -> service.update("PAYMENT_PROVIDER", "STRIPE", actor, "token"))
                    .isInstanceOf(DomainException.class);
            assertThatThrownBy(() -> service.update("DEFAULT_USER_ROLE", "USER", actor, "token"))
                    .isInstanceOf(DomainException.class);
            assertThatThrownBy(() -> service.update("AUCTION_MIN_DURATION_MINUTES", 10081, actor, "token"))
                    .isInstanceOf(DomainException.class);
            verifyNoInteractions(refresher, events);
        }
    }

    /** Tạo kho riêng từng test để quan sát trạng thái đã commit thực tế. */
    private SystemConfigStore store() throws Exception {
        return new SystemConfigStore(directory.resolve("state.json").toString(), JsonMapper.shared());
    }
}
