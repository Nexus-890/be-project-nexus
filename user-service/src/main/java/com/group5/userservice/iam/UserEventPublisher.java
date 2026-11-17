package com.group5.userservice.iam;

import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class UserEventPublisher {
    private final OutboxWriter outbox;
    private final EventEnvelopeFactory envelopes;

    /** Ghi audit cùng transaction profile, chỉ lưu actor, target và metadata không chứa bí mật. */
    public void publish(String type, UUID userId, UUID actorId, Map<String, Object> details) {
        outbox.write("User", Topics.USER, envelopes.createWithTimestamp(type, userId.toString(), instant -> {
            Map<String, Object> payload = new LinkedHashMap<>(details);
            payload.put("userId", userId);
            payload.put("actorId", actorId);
            String timestamp = switch (type) {
                case "UserDeleted" -> "deletedAt";
                case "UserRoleAssigned" -> "assignedAt";
                case "UserRegistered" -> "registeredAt";
                case "UserPasswordChanged" -> "changedAt";
                case "UserUnlocked" -> "unlockedAt";
                default -> "updatedAt";
            };
            payload.put(timestamp, instant.toString());
            return payload;
        }));
    }
}
