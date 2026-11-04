package com.group5.paymentservice.payment.entity;

import jakarta.persistence.*;
import com.group5.paymentservice.payment.enums.PaymentTransactionStatus;
import com.group5.paymentservice.payment.enums.PaymentTransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "payment_transactions", schema = "public", indexes = {
        @Index(name = "idx_payment_session",
                columnList = "session_id, created_at"),
        @Index(name = "idx_payment_txn_ref",
                columnList = "txn_reference"),
        @Index(name = "idx_payment_status",
                columnList = "status, created_at")}, uniqueConstraints = {@UniqueConstraint(name = "payment_transactions_idempotency_key_key",
        columnNames = {"idempotency_key"})})
public class PaymentTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @NotNull
    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Size(max = 40)
    @NotNull
    @Column(name = "provider", nullable = false, length = 40)
    private String provider;

    @Size(max = 120)
    @Column(name = "txn_reference", length = 120)
    private String txnReference;

    @NotNull
    @DecimalMin("0.00")
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Size(min = 3, max = 3)
    @NotNull
    @ColumnDefault("'VND'")
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency = "VND";

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "payment_transaction_status")
    private PaymentTransactionStatus status;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "type", nullable = false, columnDefinition = "payment_transaction_type")
    private PaymentTransactionType type;

    @Size(max = 100)
    @NotNull
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_response", columnDefinition = "jsonb")
    private Map<String, Object> rawResponse;

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

    @NotNull
    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "provider_occurred_at")
    private OffsetDateTime providerOccurredAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(java.time.ZoneOffset.UTC);
    }
}
