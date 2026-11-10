package com.group5.inventoryservice.reservation.repository;

import com.group5.inventoryservice.reservation.entity.InventoryReservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<InventoryReservation, UUID> {

    List<InventoryReservation> findByOrderIdInAndWarehouseId(Collection<UUID> orderIds, UUID warehouseId);
}
