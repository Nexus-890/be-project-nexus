package com.group5.inventoryservice.reservation.entity;

import com.group5.common_core.persistence.AuditedEntity;
import com.group5.inventoryservice.reservation.ReservationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Entity
@Table(name = "inventory_reservations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryReservation extends AuditedEntity {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "auction_id", updatable = false)
    private UUID auctionId;

    private UUID auctionWinnerId;

    @Column(name = "offer_id")
    private UUID offerId;

    public static InventoryReservation heldForOffer(UUID auctionId, UUID offerId, UUID bidderId, UUID skuId,
                                                    UUID warehouseId, OffsetDateTime now, OffsetDateTime expiresAt) {
        InventoryReservation row = heldForAuction(auctionId, skuId, warehouseId, now, expiresAt);
        row.offerId = offerId;
        row.auctionWinnerId = bidderId;
        return row;
    }

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "inventory_reservation_status")
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "released_at")
    private OffsetDateTime releasedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static InventoryReservation heldForAuction(UUID auctionId, UUID skuId, UUID warehouseId,
                                                       OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        InventoryReservation reservation = held(null, skuId, warehouseId, 1, createdAt, expiresAt);
        reservation.auctionId = auctionId;
        return reservation;
    }

    public void linkOrder(UUID orderId) {
        this.orderId = orderId;
    }

    public void recordAuctionWinner(UUID winnerId) {
        this.auctionWinnerId = winnerId;
    }

    public static InventoryReservation held(UUID orderId, UUID skuId, UUID warehouseId, int quantity,
                                            OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        InventoryReservation reservation = new InventoryReservation();
        reservation.orderId = orderId;
        reservation.skuId = skuId;
        reservation.warehouseId = warehouseId;
        reservation.quantity = quantity;
        reservation.status = ReservationStatus.HELD;
        reservation.createdAt = createdAt;
        reservation.expiresAt = expiresAt;
        return reservation;
    }
}
