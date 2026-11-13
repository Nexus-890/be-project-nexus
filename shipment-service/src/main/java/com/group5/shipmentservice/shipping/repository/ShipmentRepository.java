package com.group5.shipmentservice.shipping.repository;

import com.group5.shipmentservice.shipping.entity.Shipment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShipmentRepository extends JpaRepository<Shipment, UUID>, JpaSpecificationExecutor<Shipment> {
    @Modifying
    @Query(value = """
        UPDATE shipments SET last_carrier_update_at = :observedAt
        WHERE id = :id AND (last_carrier_update_at IS NULL OR last_carrier_update_at < :observedAt)
        """, nativeQuery = true)
    int recordCarrierObservation(@Param("id") UUID id, @Param("observedAt") OffsetDateTime observedAt);

    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key, 0))", nativeQuery = true)
    Integer lockKey(@Param("key") String key);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM cancelled_shipment_orders WHERE order_id = :orderId)", nativeQuery = true)
    boolean isOrderCancelled(@Param("orderId") UUID orderId);

    Optional<Shipment> findByOrderId(UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Shipment s where s.id = :id")
    Optional<Shipment> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Shipment s where s.carrier = :carrier and s.trackingCode = :trackingCode")
    Optional<Shipment> findByCarrierTrackingForUpdate(@Param("carrier") String carrier,
                                                    @Param("trackingCode") String trackingCode);

    @Query(value = """
        SELECT id FROM shipments WHERE tracking_code IS NOT NULL
          AND shipment_status NOT IN ('DELIVERED', 'CANCELLED', 'RETURNED')
          AND (next_status_poll_at IS NULL OR next_status_poll_at <= :now)
        ORDER BY next_status_poll_at NULLS FIRST, id LIMIT 100
        """, nativeQuery = true)
    List<UUID> findDueStatusPollIds(@Param("now") OffsetDateTime now);

    @Query(value = """
        SELECT * FROM shipments WHERE id = :id AND tracking_code IS NOT NULL
          AND shipment_status NOT IN ('DELIVERED', 'CANCELLED', 'RETURNED')
          AND (next_status_poll_at IS NULL OR next_status_poll_at <= :now)
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<Shipment> findDueStatusPollForUpdate(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    Optional<Shipment> findByIdempotencyKey(String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Shipment s where s.orderId = :orderId")
    Optional<Shipment> findByOrderIdForUpdate(@Param("orderId") UUID orderId);

    @Query(value = """
        SELECT id FROM shipments WHERE shipment_status = 'PENDING_TRACKING'
          AND next_tracking_retry_at <= :now AND cancel_fenced_at IS NULL
        ORDER BY next_tracking_retry_at, id LIMIT 100
        """, nativeQuery = true)
    List<UUID> findDueTrackingIds(@Param("now") OffsetDateTime now);

    @Query(value = """
        SELECT * FROM shipments WHERE id = :id AND shipment_status = 'PENDING_TRACKING'
          AND next_tracking_retry_at <= :now AND cancel_fenced_at IS NULL
          AND tracking_code IS NULL
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<Shipment> findDueTrackingForUpdate(@Param("id") UUID id, @Param("now") OffsetDateTime now);
}
