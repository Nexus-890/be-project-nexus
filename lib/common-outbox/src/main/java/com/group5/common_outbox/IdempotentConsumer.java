package com.group5.common_outbox;

import com.group5.common_events.EventEnvelope;

import jakarta.persistence.EntityManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Consumer;
/**
 *Class này dùng để đảm bảo một event không bị xử lý thành công nhiều lần bởi cùng một consumer,
 * kể cả khi Kafka gửi lại event do retry hoặc consumer khởi động lại.
 * - Đơn giản là khi Kafka gửi event tới kèm theo eventId
 * - Class này sẽ check xem event đó đã được consumer xử lý chưa
 * NẾU CHƯA - Ghi nhận event, thực hiện nghiệp vụ rồi commit transaction
 * NẾU RỒI - Bỏ qua event, không thực hiện nghiệp vụ lần nữa
 */

public class IdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumer.class);
    private static final int MAX_CONSUMER_LENGTH = 80;
    private static final String CLAIM_EVENT = """
            INSERT INTO processed_events (event_id, consumer)
            VALUES (:eventId, :consumer)
            ON CONFLICT (event_id, consumer) DO NOTHING
            """;

    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    public IdempotentConsumer(EntityManager entityManager, TransactionTemplate transactionTemplate) {
        this.entityManager = entityManager;
        this.transactionTemplate = transactionTemplate;
    }

    public <T> boolean handle(String consumer, EventEnvelope<T> envelope, Consumer<EventEnvelope<T>> handler) {
        requireConsumer(consumer);
        if (envelope == null || handler == null) {
            throw new IllegalArgumentException("envelope and handler must not be null");
        }

        Boolean handled = transactionTemplate.execute(status -> {
            if (!claim(envelope.eventId(), consumer)) {
                log.info("EVENT_ALREADY_HANDLED eventId={} eventType={} consumer={}",
                        envelope.eventId(), envelope.eventType(), consumer);
                return false;
            }
            handler.accept(envelope);
            return true;
        });
        return Boolean.TRUE.equals(handled);
    }

    private boolean claim(UUID eventId, String consumer) {
        return entityManager.createNativeQuery(CLAIM_EVENT)
                .setParameter("eventId", eventId)
                .setParameter("consumer", consumer)
                .executeUpdate() == 1;
    }

    private static void requireConsumer(String consumer) {
        if (consumer == null || consumer.isBlank()) {
            throw new IllegalArgumentException("consumer must not be blank");
        }
        if (consumer.length() > MAX_CONSUMER_LENGTH) {
            throw new IllegalArgumentException("consumer must not exceed " + MAX_CONSUMER_LENGTH + " characters");
        }
    }
}
