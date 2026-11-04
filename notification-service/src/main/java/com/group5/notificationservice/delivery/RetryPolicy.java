package com.group5.notificationservice.delivery;

import com.group5.notificationservice.shared.config.DeliveryProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class RetryPolicy {

    private final DeliveryProperties properties;

    public RetryPolicy(DeliveryProperties properties) {
        this.properties = properties;
    }

    public int limit() {
        return properties.getRetryLimit();
    }

    // LỖI VĨNH VIỄN HOẶC ĐÃ GỬI ĐỦ NOTIFICATION_RETRY_LIMIT LẦN -> KHÔNG GỬI LẠI (EMPTY = FAILED LUÔN)
    // CÒN LƯỢT: CHỜ min(maxDelay, baseDelay x 2^(n-1)) + 0..20% JITTER ĐỂ CÁC LẦN GỬI LẠI KHÔNG DỒN CÙNG LÚC
    public Optional<Instant> nextAttemptAt(int attemptNumber, boolean retryable, Instant failedAt) {
        if (!retryable || attemptNumber >= properties.getRetryLimit()) {
            return Optional.empty();
        }
        Duration delay = backoff(attemptNumber);
        long jitterMillis = (long) (delay.toMillis() * properties.getJitter() * ThreadLocalRandom.current().nextDouble());
        return Optional.of(failedAt.plus(delay).plusMillis(Math.max(jitterMillis, 1)));
    }

    Duration backoff(int attemptNumber) {
        Duration delay = properties.getBaseDelay();
        for (int i = 1; i < attemptNumber && delay.compareTo(properties.getMaxDelay()) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(properties.getMaxDelay()) > 0 ? properties.getMaxDelay() : delay;
    }
}
