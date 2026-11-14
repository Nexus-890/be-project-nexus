package com.group5.ratingservice.rating;

import com.group5.common_events.EventEnvelope;
import com.group5.common_outbox.OutboxEventKeyResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.UUID;

/** Keep ratingId as the aggregate while ordering UserRated events by their recipient. */
@Component
public class RatingEventKeyResolver implements OutboxEventKeyResolver {
    @Override
    public String resolve(EventEnvelope<JsonNode> event) {
        if (!"UserRated".equals(event.eventType())) return event.aggregateId();
        return UUID.fromString(event.payload().path("toUserId").asText()).toString();
    }
}
