package com.group5.paymentservice.payment.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "payment_pending_order_cancellations", schema = "public", indexes = {
        @Index(name = "idx_payment_pending_order_cancellations_group", columnList = "order_group_id")},
        uniqueConstraints = {
        @UniqueConstraint(name = "payment_pending_order_cancellations_order_id_key", columnNames = {"order_id"})})
public class PaymentPendingOrderCancellation {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @NotNull
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @NotNull
    @Column(name = "order_group_id", nullable = false)
    private UUID orderGroupId;

    @NotNull
    @Column(name = "buyer_id", nullable = false)
    private UUID buyerId;

    @NotNull
    @Column(name = "order_version", nullable = false)
    private Long orderVersion;

    @NotNull
    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal paidAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", length = 3, columnDefinition = "char(3)")
    private String currency;

    @Size(max = 500)
    @NotNull
    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @NotNull
    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @NotNull
    @Column(name = "cancelled_at", nullable = false)
    private OffsetDateTime cancelledAt;

    @NotNull
    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @NotNull
    @Column(name = "source_event_id", nullable = false)
    private UUID sourceEventId;

    @NotNull
    @ColumnDefault("now()")
    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @PrePersist
    void onCreate() {
        if (receivedAt == null) {
            receivedAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
