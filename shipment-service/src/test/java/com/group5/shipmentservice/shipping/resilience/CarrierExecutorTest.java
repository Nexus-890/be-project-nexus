package com.group5.shipmentservice.shipping.resilience;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.config.ShippingProperties;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CarrierExecutorTest {
    @Test
    void opensAfterFinalFailuresAndRejectsNextCallWithoutInvokingAdapter() {
        CarrierExecutor executor = executor(2, 30000);
        AtomicInteger calls = new AtomicInteger();
        for (int index = 0; index < 2; index++) {
            assertThatThrownBy(() -> executor.execute("GHN", deadline -> {
                calls.incrementAndGet();
                throw new DomainException(ShipmentErrorCode.CARRIER_UNAVAILABLE);
            })).isInstanceOf(DomainException.class)
                    .extracting(error -> ((DomainException) error).getErrorCode())
                    .isEqualTo("SHIPMENT_CARRIER_UNAVAILABLE");
        }
        long started = System.nanoTime();
        assertThatThrownBy(() -> executor.execute("GHN", deadline -> calls.incrementAndGet()))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).getErrorCode())
                .isEqualTo("SHIPMENT_CIRCUIT_OPEN");
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(200);
        assertThat(calls).hasValue(2);
        assertThat(executor.<String>execute("FAKE", deadline -> "ok")).isEqualTo("ok");
    }

    @Test
    void inputFailuresDoNotOpenBreaker() {
        CarrierExecutor executor = executor(1, 30000);
        for (int index = 0; index < 3; index++) {
            assertThatThrownBy(() -> executor.execute("GHN", deadline -> {
                throw new DomainException(ShipmentErrorCode.INVALID_ADDRESS);
            })).isInstanceOf(DomainException.class);
        }
        assertThat(executor.<String>execute("GHN", deadline -> "ok")).isEqualTo("ok");
    }

    @Test
    void catalogFailuresDoNotOpenCarrierBreaker() {
        CarrierExecutor executor = executor(1, 30000);
        for (int index = 0; index < 2; index++) {
            assertThatThrownBy(() -> executor.execute("GHN", deadline -> {
                throw new DomainException(ShipmentErrorCode.CATALOG_UNAVAILABLE);
            })).isInstanceOf(DomainException.class);
        }
        assertThat(executor.<String>execute("GHN", deadline -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> executor.execute("CATALOG", deadline -> {
            throw new DomainException(ShipmentErrorCode.CATALOG_UNAVAILABLE);
        })).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> executor.execute("CATALOG", deadline -> "ok"))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).getErrorCode())
                .isEqualTo("SHIPMENT_CIRCUIT_OPEN");
    }

    @Test
    void recoversThroughHalfOpenProbe() throws Exception {
        CarrierExecutor executor = executor(1, 30);
        assertThatThrownBy(() -> executor.execute("GHN", deadline -> {
            throw new DomainException(ShipmentErrorCode.CARRIER_UNAVAILABLE);
        })).isInstanceOf(DomainException.class);
        Thread.sleep(60);
        assertThat(executor.<String>execute("GHN", deadline -> "phuc-hoi")).isEqualTo("phuc-hoi");
        assertThat(executor.<String>execute("GHN", deadline -> "ok")).isEqualTo("ok");
    }

    @Test
    void rejectsResultsArrivingAfterSharedDeadline() {
        CarrierExecutor executor = executor(1, 30000);
        ShippingDeadline deadline = ShippingDeadline.after(Duration.ofNanos(1));
        assertThatThrownBy(() -> executor.execute("GHN", deadline, budget -> "late"))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).getErrorCode())
                .isEqualTo("SHIPMENT_CARRIER_UNAVAILABLE");
    }

    private CarrierExecutor executor(int failures, int openMs) {
        return new CarrierExecutor(new ShippingProperties(ShippingProperties.CarrierMode.GHN,
                3000, 3, 10, BigDecimal.valueOf(1_000_000), failures, openMs,
                "http://catalog-service", null, null, Map.of()));
    }
}
