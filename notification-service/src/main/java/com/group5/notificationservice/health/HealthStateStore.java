package com.group5.notificationservice.health;

import com.group5.notificationservice.shared.config.HealthProperties;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Trạng thái health dùng chung nằm trong Redis (AOF), không có bảng PostgreSQL.
 * Key {@code {env}:health:{target}} là hash status, lastCheckedAt, lastFailureAt, lastRecoveredAt, version.
 * Key {@code {env}:health:{target}:pending} giữ envelope chuyển trạng thái chưa ghi được vào event_logs.
 */
@Component
public class HealthStateStore {

    private static final String STATUS = "status";
    private static final String LAST_CHECKED_AT = "lastCheckedAt";
    private static final String LAST_FAILURE_AT = "lastFailureAt";
    private static final String LAST_RECOVERED_AT = "lastRecoveredAt";
    private static final String VERSION = "version";

    private final StringRedisTemplate redis;
    private final String environment;

    public HealthStateStore(StringRedisTemplate redis, HealthProperties properties) {
        this.redis = redis;
        this.environment = properties.environment();
    }

    public String key(String target) {
        return environment + ":health:" + target;
    }

    public String pendingKey(String target) {
        return key(target) + ":pending";
    }

    public Optional<BrokerHealth> find(String target) {
        Map<String, String> fields = redis.<String, String>opsForHash().entries(key(target));
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new BrokerHealth(target, HealthStatus.valueOf(fields.get(STATUS)),
                instant(fields.get(LAST_CHECKED_AT)), instant(fields.get(LAST_FAILURE_AT)),
                instant(fields.get(LAST_RECOVERED_AT)), Long.parseLong(fields.get(VERSION))));
    }

    public void save(BrokerHealth health) {
        redis.<String, String>opsForHash().putAll(key(health.name()), fields(health));
    }

    // TRẠNG THÁI MỚI VÀ Ý ĐỊNH GHI EVENT VÀO CÙNG 1 MULTI/EXEC
    // REDIS VÀ event_logs KHÔNG CHUNG TRANSACTION -> ENVELOPE NẰM Ở :pending ĐẾN KHI RECORDER GHI XONG
    public void saveTransition(BrokerHealth health, UUID eventId, String envelope) {
        redis.execute(new SessionCallback<List<Object>>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> List<Object> execute(RedisOperations<K, V> operations) {
                RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                ops.multi();
                ops.<String, String>opsForHash().putAll(key(health.name()), fields(health));
                ops.<String, String>opsForHash().put(pendingKey(health.name()), eventId.toString(), envelope);
                return ops.exec();
            }
        });
    }

    public Map<String, String> pendingEvents(String target) {
        return redis.<String, String>opsForHash().entries(pendingKey(target));
    }

    public void removePending(String target, String eventId) {
        redis.opsForHash().delete(pendingKey(target), eventId);
    }

    private static Map<String, String> fields(BrokerHealth health) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(STATUS, health.status().name());
        fields.put(VERSION, Long.toString(health.version()));
        fields.put(LAST_CHECKED_AT, health.lastCheckedAt().toString());
        if (health.lastFailureAt() != null) {
            fields.put(LAST_FAILURE_AT, health.lastFailureAt().toString());
        }
        if (health.lastRecoveredAt() != null) {
            fields.put(LAST_RECOVERED_AT, health.lastRecoveredAt().toString());
        }
        return fields;
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }
}
