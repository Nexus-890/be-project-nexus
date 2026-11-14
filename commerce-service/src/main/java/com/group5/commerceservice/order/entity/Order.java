package com.group5.commerceservice.order.entity;

import com.group5.commerceservice.order.OrderStatus;
import com.group5.commerceservice.order.OrderType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "orders", uniqueConstraints = @UniqueConstraint(columnNames = {"order_group_id", "seller_id"}))
public class Order {

    @Id
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_group_id", nullable = false)
    private OrderGroup orderGroup;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Column(name = "order_number", nullable = false, length = 40, unique = true)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "order_type", nullable = false, columnDefinition = "order_type")
    private OrderType orderType = OrderType.STANDARD;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "order_status")
    private OrderStatus status = OrderStatus.PENDING;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "discount_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal discountAmount = new BigDecimal("0.00");

    @Column(name = "promo_code", length = 50)
    private String promoCode;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal taxAmount = new BigDecimal("0.00");

    @Column(name = "shipping_fee", nullable = false, precision = 19, scale = 2)
    private BigDecimal shippingFee = new BigDecimal("0.00");

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shipping_info", columnDefinition = "jsonb")
    private Map<String, Object> shippingInfo;

    @Column(name = "idempotency_key", nullable = false, length = 100, unique = true)
    private String idempotencyKey;

    @Column(name = "payment_due_at")
    private OffsetDateTime paymentDueAt;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    @Column(name = "auto_complete_at")
    private OffsetDateTime autoCompleteAt;

    @Column(name = "receipt_issue_reason", length = 500)
    private String receiptIssueReason;

    @Column(name = "receipt_issue_reported_at")
    private OffsetDateTime receiptIssueReportedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "last_refund_version", nullable = false)
    private long lastRefundVersion;

    @Version
    private long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @OrderBy("id ASC")
    @BatchSize(size = 100)
    private List<OrderItem> items = new ArrayList<>();
}
