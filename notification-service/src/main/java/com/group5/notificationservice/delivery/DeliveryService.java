package com.group5.notificationservice.delivery;

import com.group5.notificationservice.delivery.DeliveryRepository.DueDelivery;
import com.group5.notificationservice.delivery.DeliveryRepository.TimedOutAttempt;
import com.group5.notificationservice.shared.config.DeliveryProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    // NHẬN TỪNG DELIVERY MỘT, NGAY TRƯỚC KHI GỬI -> LEASE TÍNH CHO ĐÚNG 1 EMAIL, KHÔNG CHO CẢ BATCH
    // TRANSACTION NÀY CHỈ GHI ATTEMPT = STARTED RỒI COMMIT, GỬI SMTP NẰM NGOÀI TRANSACTION
    @Transactional
    public Optional<Claim> claimNext(Instant now, Collection<UUID> alreadyClaimed) {
        Set<UUID> skipped = new HashSet<>(alreadyClaimed);
        for (DueDelivery delivery = nextDue(now, skipped); delivery != null; delivery = nextDue(now, skipped)) {
            skipped.add(delivery.id());
            closeTimedOutAttempts(delivery, now);
            // Người dùng có thể tắt loại email sau lần gửi lỗi; không tạo thêm attempt khi đã tắt.
            if (deliveries.skipDisabledEmail(delivery.id(), now)) {
                continue;
            }
            if (delivery.attemptCount() >= retryPolicy.limit()) {
                deliveries.markExhausted(delivery.id(), now);
                continue;
            }
            int attemptNumber = delivery.attemptCount() + 1;
            Instant leaseUntil = now.plus(properties.getAttemptTimeout());
            UUID attemptId = deliveries.startAttempt(delivery.id(), attemptNumber, leaseUntil, now);
            return Optional.of(new Claim(delivery.id(), delivery.notificationId(), attemptId, attemptNumber,
                    delivery.userId(), delivery.title(), delivery.content(), delivery.emailHtml()));
        }
        return Optional.empty();
    }

    // CHỈ GỬI KHI ATTEMPT VẪN STARTED VÀ LEASE CHƯA HẾT. HẾT LEASE = WORKER KHÁC ĐÃ ĐƯỢC PHÉP NHẬN LẠI
    @Transactional
    public boolean stillOwns(Claim claim, Instant now) {
        if (!deliveries.ownsAttempt(claim.deliveryId(), claim.attemptId(), now)) {
            return false;
        }
        // Tra cứu email có thể chậm: kiểm tra lại ngay trước SMTP và đóng attempt đã nhận nếu vừa bị tắt.
        if (deliveries.skipDisabledEmail(claim.deliveryId(), now)) {
            deliveries.failAttempt(claim.attemptId(), "PREFERENCE_DISABLED", "Email notification disabled", false, now);
            return false;
        }
        return true;
    }

    private DueDelivery nextDue(Instant now, Collection<UUID> skipped) {
        List<DueDelivery> due = deliveries.lockDueEmails(now, 1, skipped);
        return due.isEmpty() ? null : due.getFirst();
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
                        UUID recipientId, String title, String content, String emailHtml) {
    }
}
