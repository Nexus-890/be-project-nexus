package com.group5.inventoryservice.reservation.repository;

import com.group5.inventoryservice.reservation.entity.InventoryReservation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<InventoryReservation, UUID> {

    List<InventoryReservation> findByOrderIdInAndWarehouseId(Collection<UUID> orderIds, UUID warehouseId);

    List<InventoryReservation> findByOrderIdOrderBySkuIdAscIdAsc(UUID orderId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE inventory_reservations
            SET status     = 'CONFIRMED',
                updated_by = :actor
            WHERE id = :id AND status = 'HELD'
            """)
    int confirmHeld(@Param("id") UUID id, @Param("actor") UUID actor);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE inventory_reservations
            SET status      = 'RELEASED',
                released_at = now(),
                updated_by  = :actor
            WHERE id = :id AND status = 'HELD'
            """)
    int releaseHeld(@Param("id") UUID id, @Param("actor") UUID actor);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE inventory_reservations
            SET status      = 'RELEASED',
                released_at = now(),
                updated_by  = :actor
            WHERE id = :id AND status = 'CONFIRMED'
            """)
    int returnConfirmed(@Param("id") UUID id, @Param("actor") UUID actor);

    @Query("""
            SELECT r FROM InventoryReservation r
            WHERE (:orderId IS NULL OR r.orderId = :orderId)
              AND (:skuId IS NULL OR r.skuId = :skuId)
              AND (:status IS NULL OR CAST(r.status AS String) = :status)
            """)
    Page<InventoryReservation> search(@Param("orderId") UUID orderId, @Param("skuId") UUID skuId,
                                      @Param("status") String status, Pageable pageable);
}
