package com.group5.inventoryservice.reservation.service;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.inventoryservice.inventory.InventoryMovementRepository;
import com.group5.inventoryservice.inventory.MovementType;
import com.group5.inventoryservice.reservation.ReservationStatus;
import com.group5.inventoryservice.reservation.dto.response.ReservationView;
import com.group5.inventoryservice.reservation.entity.InventoryReservation;
import com.group5.inventoryservice.reservation.repository.ReservationRepository;
import com.group5.inventoryservice.shared.catalog.CatalogSkuOwners;
import com.group5.inventoryservice.shared.exception.InventoryErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReservationFulfillmentService {

    private final ReservationRepository reservations;
    private final InventoryMovementRepository movements;
    private final CatalogSkuOwners skuOwners;

    @Transactional(readOnly = true)
    public List<ReservationView> forOrder(UUID orderId, CurrentUser user) {
        List<InventoryReservation> rows = reservations.findByOrderIdOrderBySkuIdAscIdAsc(orderId);
        if (!user.hasRole("ADMIN")) {
            Map<UUID, Boolean> owned = new HashMap<>();
            if (rows.stream().anyMatch(row -> !owned.computeIfAbsent(row.getSkuId(), sku -> owns(sku, user)))) {
                throw new DomainException(InventoryErrorCode.FORBIDDEN);
            }
        }
        return rows.stream().map(ReservationView::from).toList();
    }

    @Transactional
    public ReservationView pick(UUID reservationId, CurrentUser user) {
        InventoryReservation reservation = authorized(reservationId, user);
        if (reservations.pickConfirmed(reservationId, user.id()) == 1) {
            movements.record(reservation.getSkuId(), reservation.getWarehouseId(), MovementType.PICK,
                    reservation.getQuantity(), reservationId, user.id());
        } else if (reservation.getPickedAt() == null || reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new DomainException(InventoryErrorCode.NOT_PICKABLE);
        }
        return view(reservationId);
    }

    @Transactional
    public ReservationView ship(UUID reservationId, CurrentUser user) {
        InventoryReservation reservation = authorized(reservationId, user);
        if (reservations.shipPicked(reservationId, user.id()) == 1) {
            movements.record(reservation.getSkuId(), reservation.getWarehouseId(), MovementType.SHIP,
                    reservation.getQuantity(), reservationId, user.id());
        } else if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new DomainException(InventoryErrorCode.NOT_PICKABLE);
        } else if (reservation.getPickedAt() == null) {
            throw new DomainException(InventoryErrorCode.NOT_PICKED);
        }
        return view(reservationId);
    }

    private InventoryReservation authorized(UUID reservationId, CurrentUser user) {
        InventoryReservation reservation = reservations.findById(reservationId)
                .orElseThrow(() -> new DomainException(InventoryErrorCode.RESERVATION_NOT_FOUND));
        if (!user.hasRole("ADMIN") && !owns(reservation.getSkuId(), user)) {
            throw new DomainException(InventoryErrorCode.FORBIDDEN);
        }
        return reservation;
    }

    private boolean owns(UUID skuId, CurrentUser user) {
        return skuOwners.sellerOf(skuId).filter(user.id()::equals).isPresent();
    }

    private ReservationView view(UUID reservationId) {
        return reservations.findById(reservationId).map(ReservationView::from)
                .orElseThrow(() -> new DomainException(InventoryErrorCode.RESERVATION_NOT_FOUND));
    }
}
