package com.group5.ratingservice.rating.event;

import com.group5.common_events.EventEnvelope;
import com.group5.common_outbox.OutboxEventKeyResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@Component
public class RatingEventKeyResolver implements OutboxEventKeyResolver {
    @Override
    public String resolve(EventEnvelope<JsonNode> event) {
        if (!"UserRated".equals(event.eventType())) {
            return event.aggregateId();
        }
        JsonNode recipient = event.payload().path("toUserId");
        if (!recipient.isString()) {
            throw new IllegalArgumentException("UserRated toUserId must be a UUID");
        }
        String value = recipient.asString();
        UUID id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("UserRated toUserId must be a UUID");
        }
        return id.toString();
    }
}
