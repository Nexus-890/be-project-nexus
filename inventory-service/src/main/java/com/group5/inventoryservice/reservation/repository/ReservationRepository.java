package com.group5.inventoryservice.reservation.repository;

import com.group5.inventoryservice.reservation.entity.InventoryReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<InventoryReservation, UUID> {

    List<InventoryReservation> findByOrderIdInAndWarehouseId(Collection<UUID> orderIds, UUID warehouseId);

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
}
