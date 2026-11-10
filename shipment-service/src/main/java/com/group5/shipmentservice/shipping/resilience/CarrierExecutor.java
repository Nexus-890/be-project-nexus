package com.group5.shipmentservice.shipping.resilience;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.config.ShippingProperties;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Component
public class CarrierExecutor {
    private final ShippingProperties properties;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    //class này giống cái cầu dao điện khi gọi GHN, lỗi thì ngắt and đợi
    public CarrierExecutor(ShippingProperties properties) {
        this.properties = properties;
    }

    //tạo 1 deadline mới
    //vd: executor.execute("GHN", deadline -> adapter.getProvinces(deadline))
    public <T> T execute(String carrier, Function<ShippingDeadline, T> operation) {
        return execute(carrier, ShippingDeadline.after(Duration.ofMillis(properties.apiTimeoutMs())), operation);
    }

    public boolean isAvailable(String carrier) {
        CircuitBreaker breaker = breakers.get(carrier);
        if (breaker == null) {
            return true;
        }
        return switch (breaker.getState()) {
            case CLOSED, HALF_OPEN, DISABLED, METRICS_ONLY -> true;
            case OPEN, FORCED_OPEN -> false;
        };
    }

    public <T> T execute(String carrier, ShippingDeadline deadline, Function<ShippingDeadline, T> operation) {
        //lấy breaker của carrier, nếu k có thì tạo bằng createBreaker() rồi lưu vào map
        CircuitBreaker breaker = breakers.computeIfAbsent(carrier, this::createBreaker);
        try {
            //Breaker kiểm tra có cho phép gọi không
            return breaker.executeSupplier(() -> {
                deadline.remainingOrThrow();
                T result = operation.apply(deadline);
                deadline.remainingOrThrow();
                return result;
            });
        } catch (CallNotPermittedException error) {
            throw new DomainException(ShipmentErrorCode.CIRCUIT_OPEN);
        }
    }

    private CircuitBreaker createBreaker(String carrier) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.breakerFailureThreshold())
                .minimumNumberOfCalls(properties.breakerFailureThreshold())
                .failureRateThreshold(100)
                .waitDurationInOpenState(Duration.ofMillis(properties.breakerOpenMs()))
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .permittedNumberOfCallsInHalfOpenState(1)
                .ignoreException(error -> !isAvailabilityFailure(carrier, error))
                .build();
        return CircuitBreaker.of(carrier, config);
    }

    //lỗi được tính vào breaker
    private boolean isAvailabilityFailure(String carrier, Throwable error) {
        if (!(error instanceof DomainException domain)) {
            return false;
        }
        ShipmentErrorCode code = "CATALOG".equals(carrier)
                ? ShipmentErrorCode.CATALOG_UNAVAILABLE : ShipmentErrorCode.CARRIER_UNAVAILABLE;
        return domain.getErrorCode().equals(code.code());
    }
}
