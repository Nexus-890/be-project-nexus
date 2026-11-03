package com.group5.paymentservice.payment.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.group5.paymentservice.payment.enums.PaymentInstructionStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "payment_instructions", schema = "public", indexes = {
        @Index(name = "idx_payment_instructions_session",
                columnList = "session_id, order_id"),
        @Index(name = "idx_payment_instructions_funded_transaction",
                columnList = "funded_transaction_id"),
        @Index(name = "idx_payment_instructions_buyer",
                columnList = "buyer_id, created_at"),
        @Index(name = "idx_payment_instructions_due",
                columnList = "status, due_at")}, uniqueConstraints = {@UniqueConstraint(name = "payment_instructions_order_id_key",
        columnNames = {"order_id"})})
public class PaymentInstruction {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @NotNull
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private PaymentSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "funded_transaction_id")
    private PaymentTransaction fundedTransaction;

    @NotNull
    @Column(name = "buyer_id", nullable = false)
    private UUID buyerId;

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
    @Column(name = "due_at", nullable = false)
    private OffsetDateTime dueAt;

    @ColumnDefault("'PENDING'")
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "payment_instruction_status")
    private PaymentInstructionStatus status = PaymentInstructionStatus.PENDING;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "canceled_at")
    private OffsetDateTime canceledAt;

    @Column(name = "last_order_event_at")
    private OffsetDateTime lastOrderEventAt;

    @NotNull
    @ColumnDefault("0")
    @Column(name = "last_order_version", nullable = false)
    private Long lastOrderVersion = 0L;

    @NotNull
    @ColumnDefault("0")
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

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
