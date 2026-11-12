package com.group5.shipmentservice.shipping.repository;

import com.group5.shipmentservice.shipping.entity.ShipmentHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ShipmentHistoryRepository extends JpaRepository<ShipmentHistory, UUID> {
    boolean existsByShipment_IdAndDescriptionAndSource(UUID shipmentId, String description, String source);
}
