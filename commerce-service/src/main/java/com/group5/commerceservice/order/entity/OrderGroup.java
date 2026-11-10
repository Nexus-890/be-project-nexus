package com.group5.commerceservice.order.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "order_groups")
public class OrderGroup {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "buyer_id", nullable = false)
    private UUID buyerId;

    @Column(name = "idempotency_key", nullable = false, length = 100, unique = true)
    private String idempotencyKey;

    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency = "VND";

    @Column(name = "due_at")
    private OffsetDateTime dueAt;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    // Tham chiếu logic sang Payment, không tạo FK chéo DB; cặp ID dùng để nhận diện lại đúng receipt.
    @Column(name = "payment_session_id")
    private UUID paymentSessionId;

    @Column(name = "payment_transaction_id")
    private UUID paymentTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    @Version
    private long version;

    @OneToMany(mappedBy = "orderGroup", cascade = CascadeType.ALL)
    @OrderBy("orderNumber ASC")
    private List<Order> orders = new ArrayList<>();
}
