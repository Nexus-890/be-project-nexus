package com.group5.shipmentservice.shipping.repository;

import com.group5.shipmentservice.shipping.entity.Shipment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShipmentRepository extends JpaRepository<Shipment, UUID>, JpaSpecificationExecutor<Shipment> {
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key, 0))", nativeQuery = true)
    Integer lockKey(@Param("key") String key);

    Optional<Shipment> findByOrderId(UUID orderId);

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
