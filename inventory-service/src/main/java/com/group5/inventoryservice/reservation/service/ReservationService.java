package com.group5.inventoryservice.reservation.service;

import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import com.group5.inventoryservice.inventory.InventoryBalance;
import com.group5.inventoryservice.inventory.InventoryBalanceRepository;
import com.group5.inventoryservice.inventory.InventoryMovementRepository;
import com.group5.inventoryservice.inventory.MovementType;
import com.group5.inventoryservice.inventory.WarehouseResolver;
import com.group5.inventoryservice.reservation.InventoryStockUnavailable;
import com.group5.inventoryservice.reservation.ReservationStatus;
import com.group5.inventoryservice.reservation.dto.request.ReserveRequest;
import com.group5.inventoryservice.reservation.dto.response.ReservationResponse;
import com.group5.inventoryservice.reservation.entity.InventoryReservation;
import com.group5.inventoryservice.reservation.repository.ReservationRepository;
import com.group5.inventoryservice.shared.UuidOrder;
import com.group5.inventoryservice.shared.config.ReservationTimeout;
import com.group5.inventoryservice.shared.exception.InventoryErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final OutboxWriter outbox;
    private final EventEnvelopeFactory envelopes;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public void lockAuctionOrder(UUID orderId) {
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('auction-order:'||?::text,0))",Integer.class,orderId);
    }

    public void rememberAuctionOutcome(UUID orderId,String outcome) {
        lockAuctionOrder(orderId);
        jdbc.update("""
                INSERT INTO inventory_auction_order_outcomes(order_id,outcome) VALUES (?,?)
                ON CONFLICT(order_id) DO UPDATE SET outcome=CASE
                  WHEN EXCLUDED.outcome='CANCELLED' THEN 'CANCELLED' ELSE inventory_auction_order_outcomes.outcome END
                """,orderId,outcome);
    }

    public void applyAuctionOutcome(UUID orderId) {
        for(String outcome:jdbc.queryForList("SELECT outcome FROM inventory_auction_order_outcomes WHERE order_id=?",String.class,orderId)) {
            if("CANCELLED".equals(outcome)) releaseOrder(orderId); else confirmOrder(orderId);
        }
    }

    @Transactional
    public void holdAuction(UUID auctionId, UUID skuId, Instant endAt) {
        if (reservations.existsByAuctionId(auctionId)) {
            return;
        }
        UUID warehouseId = warehouses.defaultWarehouseId();
        if (balances.reserve(skuId, warehouseId, 1, SYSTEM_ACTOR) == 0) {
            outbox.write("Auction", Topics.INVENTORY, envelopes.create("AuctionStockRejected", auctionId.toString(),
                    Map.of("auctionId", auctionId, "skuId", skuId, "reason", "OUT_OF_STOCK")));
            return;
        }
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        InventoryReservation hold = reservations.saveAndFlush(InventoryReservation.heldForAuction(
                auctionId, skuId, warehouseId, createdAt, endAt.atOffset(ZoneOffset.UTC)));
        movements.record(skuId, warehouseId, MovementType.RESERVE, 1, hold.getId(), SYSTEM_ACTOR);
        outbox.write("InventoryReservation", Topics.INVENTORY, envelopes.create("InventoryReserved", hold.getId().toString(),
                new AuctionStockReserved(hold.getId(), auctionId, null, skuId, warehouseId, 1,
                        ReservationStatus.HELD, createdAt, hold.getExpiresAt())));
    }

    @Transactional
    public void releaseAuction(UUID auctionId, String reason) {
        var existing = reservations.findForUpdateByAuctionId(auctionId);
        if (existing.isEmpty()) {
            return;
        }
        InventoryReservation hold = existing.get();
        if (hold.getOrderId() != null || reservations.releaseHeld(hold.getId(), SYSTEM_ACTOR) == 0) {
            return;
        }
        requireMoved(balances.releaseReserved(hold.getSkuId(), hold.getWarehouseId(), hold.getQuantity(),
                SYSTEM_ACTOR), hold);
        movements.record(hold.getSkuId(), hold.getWarehouseId(), MovementType.RELEASE, hold.getQuantity(),
                hold.getId(), SYSTEM_ACTOR);
        outbox.write("InventoryReservation", Topics.INVENTORY, envelopes.create("InventoryReleased", hold.getId().toString(),
                new AuctionStockReleased(hold.getId(), auctionId, null, hold.getSkuId(), hold.getWarehouseId(),
                        hold.getQuantity(), ReservationStatus.RELEASED, find(hold.getId()).getReleasedAt(), reason)));
    }

    @Transactional
    public void linkAuctionOrder(UUID auctionId, UUID orderId) {
        lockAuctionOrder(orderId);
        InventoryReservation hold = reservations.findForUpdateByAuctionId(auctionId)
                .orElseThrow(() -> new DomainException(InventoryErrorCode.RESERVATION_NOT_FOUND));
        if (orderId.equals(hold.getOrderId())) {
            return;
        }
        if (hold.getStatus() != ReservationStatus.HELD || hold.getOrderId() != null) {
            throw new DomainException(InventoryErrorCode.RESERVATION_CONFLICT);
        }
        hold.linkOrder(orderId);
        reservations.flush();
        applyAuctionOutcome(orderId);
    }

    @Transactional
    public void recordAuctionWinner(UUID auctionId, UUID winnerId) {
        InventoryReservation hold = reservations.findForUpdateByAuctionId(auctionId)
                .orElseThrow(() -> new DomainException(InventoryErrorCode.RESERVATION_NOT_FOUND));
        if (hold.getAuctionWinnerId() == null) {
            hold.recordAuctionWinner(winnerId);
        } else if (!winnerId.equals(hold.getAuctionWinnerId())) {
            throw new DomainException(InventoryErrorCode.RESERVATION_CONFLICT);
        }
    }

    private record AuctionStockReserved(UUID reservationId, UUID auctionId, UUID orderId, UUID skuId,
                                        UUID warehouseId, int quantity, ReservationStatus status,
                                        OffsetDateTime reservedAt, OffsetDateTime expiresAt) {
    }

    private record AuctionStockReleased(UUID reservationId, UUID auctionId, UUID orderId, UUID skuId,
                                        UUID warehouseId, int quantity, ReservationStatus status,
                                        OffsetDateTime releasedAt, String reason) {
    }

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
    public OrderConfirmation confirmOrder(UUID orderId) {
        List<InventoryReservation> locked = reservations.lockByOrderId(orderId);
        if (locked.stream().anyMatch(reservation -> reservation.getStatus() == ReservationStatus.RELEASED)) {
            throw new DomainException(InventoryErrorCode.ILLEGAL_TRANSITION);
        }
        List<InventoryStockUnavailable.Line> missing = missingStock(locked);
        if (!missing.isEmpty()) {
            outbox.write("Order", Topics.INVENTORY, envelopes.create("InventoryStockUnavailable", orderId.toString(),
                    new InventoryStockUnavailable(orderId, missing, Instant.now().toString())));
            return new OrderConfirmation(locked.size(), List.of(),
                    missing.stream().map(InventoryStockUnavailable.Line::reservationId).toList());
        }
        List<UUID> reclaimed = new ArrayList<>();
        for (InventoryReservation reservation : locked) {
            switch (reservation.getStatus()) {
                case HELD -> confirmAs(reservation.getId(), SYSTEM_ACTOR);
                case EXPIRED -> {
                    reclaim(reservation);
                    reclaimed.add(reservation.getId());
                }
                default -> {
                }
            }
        }
        return new OrderConfirmation(locked.size(), List.copyOf(reclaimed), List.of());
    }

    private List<InventoryStockUnavailable.Line> missingStock(List<InventoryReservation> locked) {
        List<InventoryReservation> expired = locked.stream()
                .filter(reservation -> reservation.getStatus() == ReservationStatus.EXPIRED)
                .toList();
        if (expired.isEmpty()) {
            return List.of();
        }
        Map<BalanceKey, Integer> needed = expired.stream().collect(Collectors.toMap(BalanceKey::of,
                InventoryReservation::getQuantity, Math::addExact));
        SortedMap<BalanceKey, Integer> available = new TreeMap<>(BalanceKey.LOCK_ORDER);
        locked.forEach(reservation -> available.put(BalanceKey.of(reservation), 0));
        available.replaceAll((key, ignored) -> balances.lockAvailable(key.skuId(), key.warehouseId()).orElse(0));
        return expired.stream()
                .filter(reservation -> needed.get(BalanceKey.of(reservation)) > available.get(BalanceKey.of(reservation)))
                .map(reservation -> new InventoryStockUnavailable.Line(reservation.getId(), reservation.getSkuId(),
                        reservation.getWarehouseId(), reservation.getQuantity(),
                        available.get(BalanceKey.of(reservation))))
                .toList();
    }

    private void reclaim(InventoryReservation reservation) {
        requireMoved(balances.commitAvailable(reservation.getSkuId(), reservation.getWarehouseId(),
                reservation.getQuantity(), SYSTEM_ACTOR), reservation);
        if (reservations.confirmExpired(reservation.getId(), SYSTEM_ACTOR) == 0) {
            throw new IllegalStateException("Reservation " + reservation.getId() + " is no longer EXPIRED");
        }
        movements.record(reservation.getSkuId(), reservation.getWarehouseId(), MovementType.COMMIT,
                reservation.getQuantity(), reservation.getId(), SYSTEM_ACTOR);
    }

    private record BalanceKey(UUID skuId, UUID warehouseId) {
        static final Comparator<BalanceKey> LOCK_ORDER = Comparator.comparing(BalanceKey::skuId, UuidOrder.DATABASE)
                .thenComparing(BalanceKey::warehouseId, UuidOrder.DATABASE);

        static BalanceKey of(InventoryReservation reservation) {
            return new BalanceKey(reservation.getSkuId(), reservation.getWarehouseId());
        }
    }

    public record OrderConfirmation(int reservations, List<UUID> reclaimed, List<UUID> lost) {
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
        if (reservation.getAuctionId() != null && reservation.getOrderId() != null) {
            outbox.write("InventoryReservation", Topics.INVENTORY, envelopes.createWithTimestamp(
                    "InventoryCommitted", reservationId.toString(), committedAt -> Map.of(
                            "reservationId", reservationId,
                            "auctionId", reservation.getAuctionId(),
                            "orderId", reservation.getOrderId(),
                            "skuId", reservation.getSkuId(),
                            "warehouseId", reservation.getWarehouseId(),
                            "quantity", reservation.getQuantity(),
                            "status", ReservationStatus.CONFIRMED,
                            "committedAt", committedAt)));
        }
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

    @Transactional
    public OrderRelease releaseOrder(UUID orderId) {
        int released = 0;
        int returned = 0;
        for (InventoryReservation reservation : reservations.findByOrderIdOrderBySkuIdAscIdAsc(orderId)) {
            UUID id = reservation.getId();
            if (reservations.releaseHeld(id, SYSTEM_ACTOR) == 1) {
                requireMoved(balances.releaseReserved(reservation.getSkuId(), reservation.getWarehouseId(),
                        reservation.getQuantity(), SYSTEM_ACTOR), reservation);
                movements.record(reservation.getSkuId(), reservation.getWarehouseId(), MovementType.RELEASE,
                        reservation.getQuantity(), id, SYSTEM_ACTOR);
                released++;
            } else if (reservations.returnConfirmed(id, SYSTEM_ACTOR) == 1) {
                requireMoved(balances.returnCommitted(reservation.getSkuId(), reservation.getWarehouseId(),
                        reservation.getQuantity(), SYSTEM_ACTOR), reservation);
                movements.record(reservation.getSkuId(), reservation.getWarehouseId(), MovementType.RETURN,
                        reservation.getQuantity(), id, SYSTEM_ACTOR);
                returned++;
            }
        }
        return new OrderRelease(released, returned);
    }

    public record OrderRelease(int released, int returned) {
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
                Math::addExact, () -> new TreeMap<>(UuidOrder.DATABASE)));
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
