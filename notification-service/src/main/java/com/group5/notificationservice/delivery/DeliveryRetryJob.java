package com.group5.notificationservice.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "nexus.notification", name = "email-enabled", havingValue = "true")
public class DeliveryRetryJob {

    private static final Logger log = LoggerFactory.getLogger(DeliveryRetryJob.class);

    private final DeliveryService deliveries;
    private final RecipientDirectory recipients;
    private final EmailSender sender;
    private final Clock clock;

    public DeliveryRetryJob(DeliveryService deliveries, RecipientDirectory recipients, EmailSender sender,
                            Clock clock) {
        this.deliveries = deliveries;
        this.recipients = recipients;
        this.sender = sender;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${nexus.notification.delivery.poll-interval:2s}",
            initialDelayString = "${nexus.notification.delivery.poll-interval:2s}")
    public void run() {
        runOnce();
    }

    public int runOnce() {
        List<DeliveryService.Claim> claims = deliveries.claimDue(clock.instant());
        claims.forEach(this::deliver);
        return claims.size();
    }

    private void deliver(DeliveryService.Claim claim) {
        try {
            String email = recipients.emailOf(claim.recipientId());
            String messageId = sender.send(email, claim.title(), claim.content());
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
