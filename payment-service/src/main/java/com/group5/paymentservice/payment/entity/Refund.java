package com.group5.paymentservice.payment.entity;

import com.group5.paymentservice.payment.enums.RefundStatus;
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
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "refunds", schema = "public", indexes = {
        @Index(name = "idx_refunds_status", columnList = "status, requested_at")}, uniqueConstraints = {
        @UniqueConstraint(name = "refunds_order_id_key", columnNames = {"order_id"}),
        @UniqueConstraint(name = "refunds_idempotency_key_key", columnNames = {"idempotency_key"}),
        @UniqueConstraint(name = "refunds_completed_transaction_id_key", columnNames = {"completed_transaction_id"})})
public class Refund {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @NotNull
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @NotNull
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Size(max = 3)
    @NotNull
    @ColumnDefault("'VND'")
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency = "VND";

    @NotNull
    @Column(name = "reason", nullable = false, columnDefinition = "text")
    private String reason;

    @ColumnDefault("'REQUESTED'")
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "refund_status")
    private RefundStatus status = RefundStatus.REQUESTED;

    @Size(max = 100)
    @NotNull
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @NotNull
    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @NotNull
    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "completed_by")
    private UUID completedBy;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "completed_transaction_id")
    private UUID completedTransactionId;

    // OPTIMISTIC LOCK: 0 = REQUESTED, 1 = COMPLETED -> CHÍNH LÀ refundVersion TRONG EVENT
    @NotNull
    @ColumnDefault("0")
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @NotNull
    @ColumnDefault("now()")
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @NotNull
    @ColumnDefault("'00000000-0000-0000-0000-000000000000'")
    @Column(name = "created_by", nullable = false)
    private UUID createdBy = new UUID(0, 0);

    @NotNull
    @ColumnDefault("'00000000-0000-0000-0000-000000000000'")
    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy = new UUID(0, 0);

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (requestedAt == null) requestedAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
