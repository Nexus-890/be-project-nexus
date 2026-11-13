package com.group5.catalogservice.product.entity;

import com.group5.catalogservice.product.enums.SkuStatus;
import com.group5.common_core.persistence.AuditedEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.*;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "skus", indexes = {
        @Index(name = "idx_skus_variant",
                columnList = "product_id, color, size",
                unique = true),
        @Index(name = "idx_skus_product",
                columnList = "product_id")}, uniqueConstraints = {@UniqueConstraint(name = "skus_sku_code_key",
        columnNames = {"sku_code"})})

public class Skus extends AuditedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Size(max = 60)
    @NotNull
    @Column(name = "sku_code", nullable = false, length = 60)
    private String skuCode;

    @Size(max = 50)
    @Column(name = "color", length = 50)
    private String color;

    @Size(max = 50)
    @Column(name = "size", length = 50)
    private String size;

    @NotNull
    @Column(name = "price", nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(name = "cost_price", precision = 19, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "shipping_weight_grams")
    private Integer shippingWeightGrams;

    @Column(name = "shipping_length_cm")
    private Integer shippingLengthCm;

    @Column(name = "shipping_width_cm")
    private Integer shippingWidthCm;

    @Column(name = "shipping_height_cm")
    private Integer shippingHeightCm;

    @Size(max = 3)
    @NotNull
    @ColumnDefault("'VND'")
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "VND";

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @ColumnDefault("'ACTIVE'")
    @Column(name = "status", nullable = false, columnDefinition = "sku_status")
    private SkuStatus status = SkuStatus.ACTIVE;

    @CreationTimestamp
    @ColumnDefault("now()")
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @ColumnDefault("now()")
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
