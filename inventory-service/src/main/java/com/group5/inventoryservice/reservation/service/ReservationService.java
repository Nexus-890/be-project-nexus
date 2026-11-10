package com.group5.inventoryservice.reservation.service;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.inventoryservice.inventory.InventoryBalance;
import com.group5.inventoryservice.inventory.InventoryBalanceRepository;
import com.group5.inventoryservice.inventory.InventoryMovementRepository;
import com.group5.inventoryservice.inventory.MovementType;
import com.group5.inventoryservice.inventory.WarehouseResolver;
import com.group5.inventoryservice.reservation.ReservationStatus;
import com.group5.inventoryservice.reservation.dto.request.ReserveRequest;
import com.group5.inventoryservice.reservation.dto.response.ReservationResponse;
import com.group5.inventoryservice.reservation.entity.InventoryReservation;
import com.group5.inventoryservice.reservation.repository.ReservationRepository;
import com.group5.inventoryservice.shared.config.ReservationTimeout;
import com.group5.inventoryservice.shared.exception.InventoryErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final String ORDER_ITEM_UNIQUE_INDEX = "uq_reservation_order_item";
    private static final UUID SYSTEM_ACTOR = new UUID(0, 0);

    private final InventoryBalanceRepository balances;
    private final InventoryMovementRepository movements;
    private final ReservationRepository reservations;
    private final WarehouseResolver warehouses;
    private final ReservationTimeout reservationTimeout;

    @Transactional
    public ReservationResponse reserve(ReserveRequest request, CurrentUser currentUser) {
        List<ReserveRequest.Item> items = request.items();
        rejectDuplicateItems(items);
        UUID warehouseId = warehouses.defaultWarehouseId();

        Set<UUID> orderIds = items.stream().map(ReserveRequest.Item::orderId).collect(Collectors.toSet());
        List<InventoryReservation> existing = reservations.findByOrderIdInAndWarehouseId(orderIds, warehouseId);
        if (!existing.isEmpty()) {
            return replay(items, existing, warehouseId);
        }

        UUID actor = currentUser.id();
        for (Map.Entry<UUID, Integer> sku : quantitiesBySku(items).entrySet()) {
            if (balances.reserve(sku.getKey(), warehouseId, sku.getValue(), actor) == 0) {
                throw insufficientStock(sku.getKey(), warehouseId, sku.getValue());
            }
        }

        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime expiresAt = reservationTimeout.expiresAt(createdAt);
        List<InventoryReservation> created = save(items.stream()
                .map(item -> InventoryReservation.held(item.orderId(), item.skuId(), warehouseId, item.quantity(),
                        createdAt, expiresAt))
                .toList());
        created.forEach(reservation -> movements.record(reservation.getSkuId(), warehouseId, MovementType.RESERVE,
                reservation.getQuantity(), reservation.getId(), actor));

        return new ReservationResponse(created.stream().map(InventoryReservation::getId).toList(), warehouseId,
                expiresAt);
    }

    @Transactional
    public void confirm(UUID reservationId, CurrentUser currentUser) {
        confirmAs(reservationId, currentUser.id());
    }

    @Transactional
    public int confirmOrder(UUID orderId) {
        List<InventoryReservation> held = reservations.findByOrderIdOrderBySkuIdAscIdAsc(orderId);
        held.forEach(reservation -> confirmAs(reservation.getId(), SYSTEM_ACTOR));
        return held.size();
    }

    private void confirmAs(UUID reservationId, UUID actor) {
        if (reservations.confirmHeld(reservationId, actor) == 0) {
            requireAlready(reservationId, Set.of(ReservationStatus.CONFIRMED));
            return;
        }
        InventoryReservation reservation = find(reservationId);
        requireMoved(balances.commitReserved(reservation.getSkuId(), reservation.getWarehouseId(),
                reservation.getQuantity(), actor), reservation);
        movements.record(reservation.getSkuId(), reservation.getWarehouseId(), MovementType.COMMIT,
                reservation.getQuantity(), reservation.getId(), actor);
    }

    @Transactional
    public void release(UUID reservationId, CurrentUser currentUser) {
        UUID actor = currentUser.id();
        if (reservations.releaseHeld(reservationId, actor) == 0) {
            requireAlready(reservationId, Set.of(ReservationStatus.RELEASED, ReservationStatus.EXPIRED));
            return;
        }
        InventoryReservation reservation = find(reservationId);
        requireMoved(balances.releaseReserved(reservation.getSkuId(), reservation.getWarehouseId(),
                reservation.getQuantity(), actor), reservation);
        movements.record(reservation.getSkuId(), reservation.getWarehouseId(), MovementType.RELEASE,
                reservation.getQuantity(), reservation.getId(), actor);
    }

    private void requireAlready(UUID reservationId, Set<ReservationStatus> done) {
        if (!done.contains(find(reservationId).getStatus())) {
            throw new DomainException(InventoryErrorCode.ILLEGAL_TRANSITION);
        }
    }

    private InventoryReservation find(UUID reservationId) {
        return reservations.findById(reservationId)
                .orElseThrow(() -> new DomainException(InventoryErrorCode.RESERVATION_NOT_FOUND));
    }

    private static void requireMoved(int changedRows, InventoryReservation reservation) {
        if (changedRows == 0) {
            throw new IllegalStateException("No balance row holds the reserved quantity of reservation "
                    + reservation.getId());
        }
    }

    private static void rejectDuplicateItems(List<ReserveRequest.Item> items) {
        Set<ItemKey> seen = new HashSet<>();
        for (ReserveRequest.Item item : items) {
            if (!seen.add(new ItemKey(item.orderId(), item.skuId()))) {
                throw new DomainException(InventoryErrorCode.DUPLICATE_RESERVATION_ITEM);
            }
        }
    }

    private static SortedMap<UUID, Integer> quantitiesBySku(List<ReserveRequest.Item> items) {
        return items.stream().collect(Collectors.toMap(ReserveRequest.Item::skuId, ReserveRequest.Item::quantity,
                Math::addExact, TreeMap::new));
    }

    private DomainException insufficientStock(UUID skuId, UUID warehouseId, int requested) {
        int available = balances.findBySkuIdAndWarehouseId(skuId, warehouseId)
                .map(InventoryBalance::getAvailableQuantity)
                .orElse(0);
        return new DomainException(InventoryErrorCode.INSUFFICIENT_STOCK,
                InventoryErrorCode.INSUFFICIENT_STOCK.message(),
                Map.of("skuId", skuId, "available", available, "requested", requested));
    }

    private List<InventoryReservation> save(List<InventoryReservation> rows) {
        try {
            return reservations.saveAllAndFlush(rows);
        } catch (DataIntegrityViolationException error) {
            if (String.valueOf(error.getMostSpecificCause().getMessage()).contains(ORDER_ITEM_UNIQUE_INDEX)) {
                throw new DomainException(InventoryErrorCode.RESERVATION_CONFLICT);
            }
            throw error;
        }
    }

    private static ReservationResponse replay(List<ReserveRequest.Item> items, List<InventoryReservation> existing,
                                              UUID warehouseId) {
        if (existing.stream().anyMatch(row -> row.getStatus() != ReservationStatus.HELD)) {
            throw new DomainException(InventoryErrorCode.RESERVATION_CONFLICT);
        }
        Map<ItemKey, InventoryReservation> held = existing.stream()
                .collect(Collectors.toMap(row -> new ItemKey(row.getOrderId(), row.getSkuId()), Function.identity()));
        if (held.size() != items.size()) {
            throw new DomainException(InventoryErrorCode.RESERVATION_CONFLICT);
        }
        List<UUID> ids = items.stream().map(item -> {
            InventoryReservation row = held.get(new ItemKey(item.orderId(), item.skuId()));
            if (row == null || row.getQuantity() != item.quantity()) {
                throw new DomainException(InventoryErrorCode.RESERVATION_CONFLICT);
            }
            return row.getId();
        }).toList();
        return new ReservationResponse(ids, warehouseId, existing.getFirst().getExpiresAt());
    }

    private record ItemKey(UUID orderId, UUID skuId) {
    }
}
