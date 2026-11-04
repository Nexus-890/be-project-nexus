package com.group5.notificationservice.delivery;

import com.group5.notificationservice.delivery.DeliveryRepository.DueDelivery;
import com.group5.notificationservice.delivery.DeliveryRepository.TimedOutAttempt;
import com.group5.notificationservice.shared.config.DeliveryProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DeliveryService {

    static final String EMAIL = "EMAIL";
    private static final int MAX_ERROR_MESSAGE = 500;

    private final DeliveryRepository deliveries;
    private final RetryPolicy retryPolicy;
    private final DeliveryProperties properties;
    private final DeliveryEventRecorder events;

    public DeliveryService(DeliveryRepository deliveries, RetryPolicy retryPolicy, DeliveryProperties properties,
                           DeliveryEventRecorder events) {
        this.deliveries = deliveries;
        this.retryPolicy = retryPolicy;
        this.properties = properties;
        this.events = events;
    }

    // TRANSACTION NÀY CHỈ GHI ATTEMPT = STARTED RỒI COMMIT, GỬI SMTP NẰM NGOÀI TRANSACTION
    @Transactional
    public List<Claim> claimDue(Instant now) {
        List<Claim> claims = new ArrayList<>();
        for (DueDelivery delivery : deliveries.lockDueEmails(now, properties.getBatchSize())) {
            closeTimedOutAttempts(delivery, now);
            if (delivery.attemptCount() >= retryPolicy.limit()) {
                deliveries.markExhausted(delivery.id(), now);
                continue;
            }
            int attemptNumber = delivery.attemptCount() + 1;
            UUID attemptId = deliveries.startAttempt(delivery.id(), attemptNumber,
                    now.plus(properties.getAttemptTimeout()), now);
            claims.add(new Claim(delivery.id(), delivery.notificationId(), attemptId, attemptNumber,
                    delivery.userId(), delivery.title(), delivery.content()));
        }
        return claims;
    }

    @Transactional
    public void succeeded(Claim claim, String providerMessageId, Instant now) {
        if (!deliveries.completeAttempt(claim.attemptId(), providerMessageId, now)) {
            return;
        }
        deliveries.markSent(claim.deliveryId(), now);
        events.sent(new DeliveryEvents.Sent(claim.notificationId(), claim.deliveryId(), claim.attemptId(),
                claim.recipientId(), EMAIL, claim.attemptNumber(), providerMessageId, now));
    }

    @Transactional
    public void failed(Claim claim, DeliveryFailure failure, Instant now) {
        if (!deliveries.failAttempt(claim.attemptId(), failure.errorCode(), errorMessage(failure),
                failure.retryable(), now)) {
            return;
        }
        Optional<Instant> next = retryPolicy.nextAttemptAt(claim.attemptNumber(), failure.retryable(), now);
        String status = next.isPresent() ? "PENDING" : "FAILED";
        deliveries.reschedule(claim.deliveryId(), status, next.orElse(null), now);
        events.failed(new DeliveryEvents.Failed(claim.notificationId(), claim.deliveryId(), claim.attemptId(),
                claim.recipientId(), EMAIL, claim.attemptNumber(), failure.errorCode(), failure.retryable(),
                status, next.orElse(null), now));
    }

    // ATTEMPT CÒN STARTED MÀ ĐÃ QUÁ HẠN = WORKER TRƯỚC CHẾT GIỮA CHỪNG -> ĐÓNG LÀ FAILED (TẠM THỜI), KHÔNG COI LÀ ĐÃ GỬI
    private void closeTimedOutAttempts(DueDelivery delivery, Instant now) {
        for (TimedOutAttempt attempt : deliveries.closeStartedAttempts(delivery.id(), now)) {
            boolean budgetLeft = delivery.attemptCount() < retryPolicy.limit();
            events.failed(new DeliveryEvents.Failed(delivery.notificationId(), delivery.id(), attempt.id(),
                    delivery.userId(), EMAIL, attempt.attemptNumber(), "ATTEMPT_TIMED_OUT", true,
                    budgetLeft ? "PENDING" : "FAILED", budgetLeft ? now : null, now));
        }
    }

    private static String errorMessage(DeliveryFailure failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        String message = Emails.mask(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        return message.length() > MAX_ERROR_MESSAGE ? message.substring(0, MAX_ERROR_MESSAGE) : message;
    }

    public record Claim(UUID deliveryId, UUID notificationId, UUID attemptId, int attemptNumber,
                        UUID recipientId, String title, String content) {
    }
}
