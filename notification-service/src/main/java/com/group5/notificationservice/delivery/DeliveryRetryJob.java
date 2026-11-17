package com.group5.notificationservice.delivery;

import com.group5.notificationservice.shared.config.DeliveryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "nexus.notification", name = "email-enabled", havingValue = "true")
public class DeliveryRetryJob {

    private static final Logger log = LoggerFactory.getLogger(DeliveryRetryJob.class);

    private final DeliveryService deliveries;
    private final RecipientDirectory recipients;
    private final EmailSender sender;
    private final Clock clock;
    private final int batchSize;

    public DeliveryRetryJob(DeliveryService deliveries, RecipientDirectory recipients, EmailSender sender,
                            Clock clock, DeliveryProperties properties) {
        this.deliveries = deliveries;
        this.recipients = recipients;
        this.sender = sender;
        this.clock = clock;
        this.batchSize = properties.getBatchSize();
    }

    @Scheduled(fixedDelayString = "${nexus.notification.delivery.poll-interval:2s}",
            initialDelayString = "${nexus.notification.delivery.poll-interval:2s}")
    public void run() {
        runOnce();
    }

    // MỖI LƯỢT NHẬN TỐI ĐA batchSize DELIVERY, MỖI DELIVERY TỐI ĐA 1 LẦN
    public int runOnce() {
        Set<UUID> claimed = new HashSet<>();
        while (claimed.size() < batchSize) {
            Optional<DeliveryService.Claim> claim = deliveries.claimNext(clock.instant(), claimed);
            if (claim.isEmpty()) {
                break;
            }
            claimed.add(claim.get().deliveryId());
            deliver(claim.get());
        }
        return claimed.size();
    }

    private void deliver(DeliveryService.Claim claim) {
        try {
            String email = recipients.emailOf(claim.recipientId());
            if (!deliveries.stillOwns(claim, clock.instant())) {
                log.info("NOTIFICATION_DELIVERY_NOT_SENDABLE deliveryId={} attempt={}",
                        claim.deliveryId(), claim.attemptNumber());
                return;
            }
            String messageId = claim.emailHtml() == null
                    ? sender.send(email, claim.title(), claim.content())
                    : sender.send(email, claim.title(), claim.emailHtml(), true);
            deliveries.succeeded(claim, messageId, clock.instant());
            log.info("NOTIFICATION_DELIVERY_SENT deliveryId={} attempt={} to={}",
                    claim.deliveryId(), claim.attemptNumber(), Emails.mask(email));
        } catch (DeliveryFailure failure) {
            deliveries.failed(claim, failure, clock.instant());
            log.warn("NOTIFICATION_DELIVERY_FAILED deliveryId={} attempt={} errorCode={} retryable={}",
                    claim.deliveryId(), claim.attemptNumber(), failure.errorCode(), failure.retryable());
        } catch (RuntimeException exception) {
            deliveries.failed(claim, new DeliveryFailure("DELIVERY_ERROR", true, exception), clock.instant());
            log.warn("NOTIFICATION_DELIVERY_FAILED deliveryId={} attempt={} errorCode=DELIVERY_ERROR",
                    claim.deliveryId(), claim.attemptNumber());
        }
    }
}
