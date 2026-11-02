package com.group5.paymentservice.payment.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "payment_pending_cancellations", schema = "public", indexes = {
        @Index(name = "idx_payment_pending_cancellations_buyer",
                columnList = "buyer_id, received_at")}, uniqueConstraints = {
        @UniqueConstraint(name = "payment_pending_cancellations_order_group_id_key",
                columnNames = {"order_group_id"})})
public class PaymentPendingCancellation {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @NotNull
    @Column(name = "order_group_id", nullable = false)
    private UUID orderGroupId;

    @NotNull
    @Column(name = "buyer_id", nullable = false)
    private UUID buyerId;

    @NotNull
    @Column(name = "group_version", nullable = false)
    private Long groupVersion;

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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "orders", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, String>> orders;

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
