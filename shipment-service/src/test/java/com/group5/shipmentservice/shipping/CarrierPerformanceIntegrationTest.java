package com.group5.shipmentservice.shipping;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.ShipmentServiceApplication;
import com.group5.shipmentservice.shipping.dto.response.CarrierPerformanceResponse;
import com.group5.shipmentservice.shipping.enums.ShipmentStatus;
import com.group5.shipmentservice.shipping.service.CarrierPerformanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(classes = ShipmentServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.config.name=carrier-performance-test", "spring.config.import=",
                "spring.cloud.config.enabled=false", "spring.cloud.discovery.enabled=false",
                "eureka.client.enabled=false", "spring.cloud.compatibility-verifier.enabled=false",
                "spring.jpa.hibernate.ddl-auto=validate", "nexus.shipping.carrier=FAKE",
                "nexus.shipping.order-paid-consumer-enabled=false",
                "nexus.shipping.order-cancelled-consumer-enabled=false", "nexus.outbox.relay.enabled=false",
                "nexus.shipping.status-poll-enabled=false", "nexus.shipping.status-poll-interval-ms=3600000",
                "nexus.shipping.tracking-retry-poll-ms=3600000", "management.tracing.export.zipkin.enabled=false"
        })
class CarrierPerformanceIntegrationTest {
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CarrierPerformanceService performance;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE carrier_webhook_receipts, cancelled_shipment_orders, shipment_history, shipments, outbox, processed_events");
    }

    @Test
    void successRateCountsOnlyFinishedShipmentsAndDeliveryTimeStartsAtHandover() {
        delivered("GHN", T0.plusHours(1), T0.plusHours(2), T0.plusHours(26), T0.plusHours(30));
        delivered("GHN", T0.plusHours(3), null, T0.plusHours(51), T0.plusHours(40));
        shipment("GHN", ShipmentStatus.RETURNED, T0.plusHours(4), T0.plusHours(5), null);
        shipment("GHN", ShipmentStatus.IN_TRANSIT, T0.plusHours(6), T0.plusHours(7), null);
        shipment("GHN", ShipmentStatus.CANCELLED, T0.plusHours(8), null, null);
        shipment("GHN", ShipmentStatus.DELIVERED, T0.minusDays(1), T0.minusDays(1), null);

        CarrierPerformanceResponse ghn = row(performance.performance(T0, T0.plusDays(7)), "GHN");

        assertThat(ghn.shipments()).isEqualTo(5);
        assertThat(ghn.delivered()).isEqualTo(2);
        assertThat(ghn.failed()).isEqualTo(1);
        assertThat(ghn.inProgress()).isEqualTo(1);
        assertThat(ghn.cancelled()).isEqualTo(1);
        assertThat(ghn.successRate()).isEqualByComparingTo("0.6667");
        assertThat(ghn.averageDeliveryHours()).isEqualByComparingTo("36.0");
        assertThat(ghn.onTimeRate()).isEqualByComparingTo("0.5000");
    }

    @Test
    void configuredCarriersWithoutShipmentsAppearWithZerosAndBusiestCarrierComesFirst() {
        shipment("GHTK", ShipmentStatus.IN_TRANSIT, T0.plusHours(1), null, null);
        shipment("GHTK", ShipmentStatus.CREATED, T0.plusHours(2), null, null);
        shipment("GHN", ShipmentStatus.CREATED, T0.plusHours(3), null, null);

        List<CarrierPerformanceResponse> rows = performance.performance(T0, T0.plusDays(1));

        assertThat(rows).extracting(CarrierPerformanceResponse::carrier).containsExactly("GHTK", "GHN", "FAKE");
        CarrierPerformanceResponse fake = row(rows, "FAKE");
        assertThat(fake.name()).isEqualTo("Simulated carrier");
        assertThat(fake.shipments()).isZero();
        assertThat(fake.successRate()).isNull();
        assertThat(fake.averageDeliveryHours()).isNull();
        assertThat(row(rows, "GHTK").successRate()).isNull();
    }

    @Test
    void theWindowIncludesFromExcludesToAndMustBeAtMost366Days() {
        shipment("GHN", ShipmentStatus.CREATED, T0, null, null);
        shipment("GHN", ShipmentStatus.CREATED, T0.plusDays(1), null, null);

        assertThat(row(performance.performance(T0, T0.plusDays(1)), "GHN").shipments()).isEqualTo(1);
        for (OffsetDateTime[] bad : new OffsetDateTime[][]{{T0, T0}, {T0.plusDays(1), T0}, {T0, T0.plusDays(367)},
                {null, T0}}) {
            assertThatThrownBy(() -> performance.performance(bad[0], bad[1]))
                    .isInstanceOfSatisfying(DomainException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo("SHIPMENT_INVALID_PERIOD"));
        }
        assertThat(performance.performance(T0, T0.plusDays(366))).isNotEmpty();
    }

    private static CarrierPerformanceResponse row(List<CarrierPerformanceResponse> rows, String carrier) {
        return rows.stream().filter(row -> row.carrier().equals(carrier)).findFirst().orElseThrow();
    }

    private void delivered(String carrier, OffsetDateTime created, OffsetDateTime handedOver,
                           OffsetDateTime deliveredAt, OffsetDateTime estimate) {
        UUID id = shipment(carrier, ShipmentStatus.DELIVERED, created, handedOver, estimate);
        jdbc.update("""
                INSERT INTO shipment_history (shipment_id, shipment_status, created_at)
                VALUES (?, 'DELIVERED'::shipment_status, ?)
                """, id, Timestamp.from(deliveredAt.toInstant()));
        jdbc.update("""
                INSERT INTO shipment_history (shipment_id, shipment_status, created_at)
                VALUES (?, 'DELIVERED'::shipment_status, ?)
                """, id, Timestamp.from(deliveredAt.plusHours(5).toInstant()));
    }

    private UUID shipment(String carrier, ShipmentStatus status, OffsetDateTime created, OffsetDateTime handedOver,
                          OffsetDateTime estimate) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO shipments (id, order_id, buyer_id, warehouse_id, carrier, shipment_status, shipping_info,
                                       created_at, handed_over_at, estimated_delivery_at)
                VALUES (?, ?, ?, ?, ?, ?::shipment_status, '{}'::jsonb, ?, ?, ?)
                """, id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), carrier, status.name(),
                Timestamp.from(created.toInstant()), handedOver == null ? null : Timestamp.from(handedOver.toInstant()),
                estimate == null ? null : Timestamp.from(estimate.toInstant()));
        return id;
    }
}
