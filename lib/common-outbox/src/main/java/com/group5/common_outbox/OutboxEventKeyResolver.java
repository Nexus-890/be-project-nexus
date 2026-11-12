package com.group5.common_outbox;

import com.group5.common_events.EventEnvelope;
import tools.jackson.databind.JsonNode;

/** Chọn key phân vùng Kafka mà vẫn giữ nguyên envelope đã lưu trong outbox. */
@FunctionalInterface
public interface OutboxEventKeyResolver {
    /** Trả về key theo nghiệp vụ của service; aggregateId trong event không bị thay đổi. */
    String resolve(EventEnvelope<JsonNode> event);
}
