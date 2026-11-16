package com.group5.configservice.systemconfig;

import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

@Service
public class SystemConfigService {
    private static final Logger log = LoggerFactory.getLogger(SystemConfigService.class);
    private static final Set<String> KEYCLOAK_KEYS = Set.of("SESSION_TIMEOUT_MINUTES", "MAX_LOGIN_ATTEMPTS",
            "PASSWORD_POLICY", "ACCESS_TOKEN_EXPIRY_MINUTES");
    private final SystemConfigStore store;
    private final RuntimeEnvironmentRepository environments;
    private final SystemConfigRefresher refresher;
    private final EventEnvelopeFactory events;
    private final KeycloakRealmConfigClient keycloak;

    /** Nhận kho bền vững, Config Server và các client có sẵn; không truy cập DB của service khác. */
    public SystemConfigService(SystemConfigStore store, RuntimeEnvironmentRepository environments,
                               SystemConfigRefresher refresher, EventEnvelopeFactory events, KeycloakRealmConfigClient keycloak) {
        this.store = store; this.environments = environments; this.refresher = refresher; this.events = events;
        this.keycloak = keycloak;
    }

    /** Liệt kê toàn bộ key SRS, tách giá trị đang cấu hình khỏi default nguyên bản của SRS. */
    public synchronized List<ConfigResponse> list() {
        Map<String, StandardEnvironment> sources = new LinkedHashMap<>();
        Map<String, Object> values = store.values();
        Map<String, Object> realmValues = keycloak.currentValues();
        return SystemConfigKey.all().stream().map(key -> {
            Object current = values.containsKey(key.key()) ? values.get(key.key())
                    : current(key, sources.computeIfAbsent(key.service(), this::environment));
            boolean keycloak = KEYCLOAK_KEYS.contains(key.key());
            String mode = keycloak ? "KEYCLOAK_MANAGED"
                    : key.key().equals("NOTIFICATION_RETRY_LIMIT") ? "RUNTIME" : "CONFIG_SOURCE";
            String description = key.description() + (keycloak
                    ? "; effective value is read from Keycloak; configuredValue is the Config Server source" : "");
            return new ConfigResponse(key.key(), keycloak ? realmValues.get(key.key()) : current, key.defaultValue(), description,
                    key.service(), mode, current);
        }).toList();
    }

    /** Xác minh kiểu, refresh owner rồi commit override và audit trong cùng snapshot nguyên tử. */
    public synchronized UpdatedResponse update(String name, Object value, UUID actor, String token) {
        SystemConfigKey key = SystemConfigKey.require(name);
        if (KEYCLOAK_KEYS.contains(name)) throw new DomainException(ConfigErrorCode.CONFIG_KEY_EXTERNALLY_MANAGED);
        Object accepted = key.validate(value);
        Map<String, Object> stored = store.values();
        validateSupportedSource(name, accepted, stored);
        Object before = stored.containsKey(name) ? stored.get(name) : current(key, environment(key.service()));
        if (sameValue(before, accepted)) return new UpdatedResponse(name, before);
        EventEnvelope<?> event = events.createWithTimestamp("SystemConfigUpdated", name,
                changedAt -> new ConfigUpdated(name, key.service(), before, accepted, actor, changedAt));
        try {
            store.stage(name, accepted, event);
            environments.tentative(name, accepted);
            refreshOwners(key, token);
            store.commit(name, accepted, event);
            return new UpdatedResponse(name, accepted);
        } catch (Exception failure) {
            environments.clearTentative();
            try { store.abort(); }
            catch (Exception rollback) { failure.addSuppressed(rollback); }
            try { refreshOwners(key, token); }
            catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
            log.error("Could not apply system configuration {}", name, failure);
            throw new DomainException(ConfigErrorCode.CONFIG_UPDATE_FAILED);
        } finally { environments.clearTentative(); }
    }

    /** Giữ thứ tự ưu tiên của Config Server và giải placeholder bằng môi trường deployment. */
    private StandardEnvironment environment(String service) {
        StandardEnvironment result = new StandardEnvironment();
        for (var source : environments.baseline(service).getPropertySources()) {
            Map<String, Object> values = new LinkedHashMap<>();
            source.getSource().forEach((key, value) -> values.put(String.valueOf(key), value));
            result.getPropertySources().addLast(new MapPropertySource(source.getName(), values));
        }
        return result;
    }

    /** Đọc kiểu cấu hình đang triển khai; key chưa có consumer vẫn hiển thị default SRS. */
    private Object current(SystemConfigKey key, StandardEnvironment environment) {
        String value = environment.getProperty(key.property());
        if (value == null) return key.defaultValue();
        return switch (key.kind()) {
            case BOOLEAN -> Boolean.valueOf(value);
            case INTEGER -> Integer.valueOf(value);
            case DECIMAL -> new BigDecimal(value);
            case STRING -> value;
        };
    }

    /** So sánh số theo giá trị, tránh tạo audit giả vì scale JSON khác nhau. */
    private boolean sameValue(Object before, Object after) {
        if (before instanceof Number && after instanceof Number) {
            return new BigDecimal(before.toString()).compareTo(new BigDecimal(after.toString())) == 0;
        }
        return java.util.Objects.equals(before, after);
    }

    /** Ngưỡng miễn ship được cả quote và checkout sử dụng nên phải refresh hai owner. */
    private void refreshOwners(SystemConfigKey key, String token) {
        refresher.refresh(key.service(), token);
        if (key.key().equals("FREE_SHIPPING_THRESHOLD")) refresher.refresh("commerce-service", token);
    }

    /** Chặn giá trị nguồn mà consumer hiện tại không hỗ trợ dù vẫn giữ default lịch sử trong GET. */
    private void validateSupportedSource(String name, Object value, Map<String, Object> stored) {
        if (name.equals("PAYMENT_PROVIDER") && !value.equals("SEPAY")) {
            throw new DomainException(ConfigErrorCode.CONFIG_VALUE_INVALID);
        }
        if (name.equals("DEFAULT_USER_ROLE") && value.equals("USER")) {
            throw new DomainException(ConfigErrorCode.CONFIG_VALUE_INVALID);
        }
        if (name.equals("AUCTION_MIN_DURATION_MINUTES") || name.equals("AUCTION_MAX_DURATION_HOURS")) {
            String otherName = name.equals("AUCTION_MIN_DURATION_MINUTES")
                    ? "AUCTION_MAX_DURATION_HOURS" : "AUCTION_MIN_DURATION_MINUTES";
            SystemConfigKey other = SystemConfigKey.require(otherName);
            Object otherValue = stored.containsKey(otherName) ? stored.get(otherName)
                    : current(other, environment(other.service()));
            long minimum = ((Number) (name.equals("AUCTION_MIN_DURATION_MINUTES") ? value : otherValue)).longValue();
            long maximum = ((Number) (name.equals("AUCTION_MAX_DURATION_HOURS") ? value : otherValue)).longValue();
            if (minimum > maximum * 60L) throw new DomainException(ConfigErrorCode.CONFIG_VALUE_INVALID);
        }
    }

    public record ConfigResponse(String key, Object value, Object defaultValue, String description, String service,
                                 String applyMode, Object configuredValue) { }
    public record UpdatedResponse(String key, Object value) { }
    public record ConfigUpdated(String configKey, String serviceName, Object oldValue, Object newValue,
                                UUID actorId, Instant changedAt) { }
}
