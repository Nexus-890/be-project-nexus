package com.group5.shipmentservice.shipping.repository;

import com.group5.shipmentservice.shipping.entity.ShipmentHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface ShipmentHistoryRepository extends JpaRepository<ShipmentHistory, UUID> {
    boolean existsByShipment_IdAndDescriptionAndSource(UUID shipmentId, String description, String source);

    List<ShipmentHistory> findByShipment_IdOrderByCreatedAtAscIdAsc(UUID shipmentId);
    Page<ShipmentHistory> findByShipment_Id(UUID shipmentId, Pageable pageable);

    boolean existsByShipment_IdAndCarrierEventId(UUID shipmentId, String carrierEventId);
}
