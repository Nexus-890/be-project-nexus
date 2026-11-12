package com.group5.shipmentservice.shipping.entity;

import com.group5.shipmentservice.shared.persistence.AuditedEntity;
import com.group5.shipmentservice.shipping.enums.ShipmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Entity
@Table(name = "shipments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shipment extends AuditedEntity {

    // Writers/backfills supply stable IDs; native inserts may use the V1 uuidv7() default.
    @Id
    @ColumnDefault("uuidv7()")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "carrier", nullable = false, length = 40)
    private String carrier;

    @Column(name = "tracking_code", length = 80)
    private String trackingCode;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "shipment_status", nullable = false, columnDefinition = "shipment_status")
    private ShipmentStatus shipmentStatus = ShipmentStatus.CREATED;

    @Column(name = "service_type", length = 40)
    private String serviceType;

    @Column(name = "shipping_fee", nullable = false, precision = 19, scale = 2)
    private BigDecimal shippingFee = BigDecimal.ZERO;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "buyer_id", nullable = false)
    private UUID buyerId;

    @Column(name = "seller_id")
    private UUID sellerId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shipping_info", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> shippingInfo;

    @Column(name = "idempotency_key", length = 100, unique = true)
    private String idempotencyKey;

    @Column(name = "idempotency_fingerprint", length = 64)
    private String idempotencyFingerprint;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "tracking_retry_count", nullable = false)
    private int trackingRetryCount;

    @Column(name = "next_tracking_retry_at")
    private OffsetDateTime nextTrackingRetryAt;

    @Column(name = "cancel_request_id")
    private UUID cancelRequestId;

    @Column(name = "cancel_fenced_at")
    private OffsetDateTime cancelFencedAt;

    @Column(name = "handed_over_at")
    private OffsetDateTime handedOverAt;

    @Column(name = "last_carrier_update_at", updatable = false)
    private OffsetDateTime lastCarrierUpdateAt;

    @Column(name = "next_status_poll_at")
    private OffsetDateTime nextStatusPollAt;

    @Column(name = "status_poll_failures", nullable = false)
    private int statusPollFailures;

    public static Shipment create(UUID id, UUID orderId, UUID buyerId, UUID warehouseId,
                                  String carrier, String serviceType, BigDecimal shippingFee,
                                  Map<String, Object> shippingInfo, String key) {
        Shipment shipment = new Shipment();
        shipment.id = id;
        shipment.orderId = orderId;
        shipment.buyerId = buyerId;
        shipment.warehouseId = warehouseId;
        shipment.carrier = carrier;
        shipment.serviceType = serviceType;
        shipment.shippingFee = shippingFee;
        shipment.shippingInfo = shippingInfo;
        shipment.idempotencyKey = key;
        shipment.shipmentStatus = ShipmentStatus.PENDING_TRACKING;
        return shipment;
    }

    public void trackingAssigned(String code) {
        trackingCode = code;
        shipmentStatus = ShipmentStatus.CREATED;
        nextTrackingRetryAt = null;
    }

    public void trackingPending(OffsetDateTime nextAttempt) {
        shipmentStatus = ShipmentStatus.PENDING_TRACKING;
        nextTrackingRetryAt = nextAttempt;
    }

    public void countTrackingRetry() { trackingRetryCount++; }

    public void changeStatus(ShipmentStatus status, OffsetDateTime changedAt, OffsetDateTime observedAt,
                             boolean pickedUp) {
        shipmentStatus = status;
        if (pickedUp && handedOverAt == null) handedOverAt = changedAt;
        if (observedAt != null) lastCarrierUpdateAt = observedAt;
        if (status == ShipmentStatus.CANCELLED) nextTrackingRetryAt = null;
    }

    public void scheduleStatusPoll(OffsetDateTime nextAttempt, boolean failed) {
        nextStatusPollAt = nextAttempt;
        statusPollFailures = failed ? Math.min(statusPollFailures + 1, 10) : 0;
    }

    public void claimManualRequest(String key, String fingerprint) {
        idempotencyKey = key;
        idempotencyFingerprint = fingerprint;
    }

    public void assignSeller(UUID trustedSellerId) {
        if (trustedSellerId == null || (sellerId != null && !sellerId.equals(trustedSellerId))) {
            throw new IllegalArgumentException("Conflicting or missing shipment seller");
        }
        sellerId = trustedSellerId;
    }
}
