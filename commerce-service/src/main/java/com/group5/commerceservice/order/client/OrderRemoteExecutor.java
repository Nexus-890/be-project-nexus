package com.group5.commerceservice.order.client;

import com.group5.common_core.constant.ErrorCode;
import com.group5.common_core.exception.DomainException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Chỉ lỗi khả dụng mới mở cầu dao; thiếu tồn kho và địa chỉ sai vẫn được trả đúng nghiệp vụ. */
@Component
public class OrderRemoteExecutor {
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final int failureThreshold;
    private final Duration openDuration;

    public OrderRemoteExecutor(@Value("${nexus.order.remote-breaker-failure-threshold:5}") int failureThreshold,
                               @Value("${nexus.order.remote-breaker-open-ms:30000}") long openMillis) {
        if (failureThreshold < 1 || openMillis < 1) throw new IllegalArgumentException("Invalid order circuit breaker configuration");
        this.failureThreshold = failureThreshold;
        this.openDuration = Duration.ofMillis(openMillis);
    }

    public <T> T execute(String service, ErrorCode unavailable, Supplier<T> operation) {
        CircuitBreaker breaker = breakers.computeIfAbsent(service, name -> CircuitBreaker.of(name,
                CircuitBreakerConfig.custom().slidingWindowSize(failureThreshold)
                        .minimumNumberOfCalls(failureThreshold).failureRateThreshold(100)
                        .waitDurationInOpenState(openDuration).permittedNumberOfCallsInHalfOpenState(1)
                        .ignoreException(error -> !(error instanceof DomainException domain)
                                || !unavailable.code().equals(domain.getErrorCode())).build()));
        try {
            return breaker.executeSupplier(operation);
        } catch (CallNotPermittedException error) {
            throw new DomainException(unavailable);
        }
    }
}
