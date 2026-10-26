package com.group5.common_outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_events")
@IdClass(ProcessedEventId.class)
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Id
    @Column(name = "consumer", nullable = false, updatable = false, length = 80)
    private String consumer;

    @Column(name = "handled_at", nullable = false, insertable = false, updatable = false)
    private Instant handledAt;

    protected ProcessedEvent() {
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getConsumer() {
        return consumer;
    }

    public Instant getHandledAt() {
        return handledAt;
    }
}
