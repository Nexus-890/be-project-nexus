package com.group5.common_outbox;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;


//CLASS này dùng để định nghĩa 1 key của event + consumer ví dụ
// - event: abc
// - consumer: notification-service
// KEY -> abc-123 + notification-service
public class ProcessedEventId implements Serializable {

    private UUID eventId;
    private String consumer;

    protected ProcessedEventId() {
    }

    public ProcessedEventId(UUID eventId, String consumer) {
        this.eventId = eventId;
        this.consumer = consumer;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getConsumer() {
        return consumer;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProcessedEventId that)) {
            return false;
        }
        return Objects.equals(eventId, that.eventId) && Objects.equals(consumer, that.consumer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, consumer);
    }
}
