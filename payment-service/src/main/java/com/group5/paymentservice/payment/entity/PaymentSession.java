package com.group5.paymentservice.payment.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.group5.paymentservice.payment.enums.PaymentSessionStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "payment_sessions", schema = "public", indexes = {
        @Index(name = "idx_payment_sessions_buyer",
                columnList = "buyer_id, created_at"),
        @Index(name = "idx_payment_sessions_due",
                columnList = "status, due_at")}, uniqueConstraints = {
        @UniqueConstraint(name = "payment_sessions_order_group_id_key",
                columnNames = {"order_group_id"}),
        @UniqueConstraint(name = "payment_sessions_memo_code_key",
                columnNames = {"memo_code"}),
        @UniqueConstraint(name = "payment_sessions_idempotency_key_key",
                columnNames = {"idempotency_key"})})
public class PaymentSession {
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
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Size(max = 3)
    @NotNull
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency = "VND";

    @Size(max = 32)
    @NotNull
    @Column(name = "memo_code", nullable = false, length = 32)
    private String memoCode;

    @NotNull
    @Column(name = "due_at", nullable = false)
    private OffsetDateTime dueAt;

    @ColumnDefault("'PENDING'")
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "payment_session_status")
    private PaymentSessionStatus status = PaymentSessionStatus.PENDING;

    @NotNull
    @ColumnDefault("0")
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @NotNull
    @Column(name = "source_event_id", nullable = false)
    private UUID sourceEventId;

    @Size(max = 100)
    @NotNull
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @NotNull
    @ColumnDefault("now()")
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

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
        OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now(java.time.ZoneOffset.UTC);
    }
}
