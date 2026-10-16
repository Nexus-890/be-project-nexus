package com.group5.catalogservice.product.entity;

import com.group5.catalogservice.category.Category;
import com.group5.catalogservice.product.enums.ProductStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "products", indexes = {
        @Index(name = "idx_products_category",
                columnList = "category_id, status"),
        @Index(name = "idx_products_seller",
                columnList = "seller_id, status"),
        @Index(name = "idx_products_status",
                columnList = "status, deleted_at")}, uniqueConstraints = {@UniqueConstraint(name = "products_slug_key",
        columnNames = {"slug"})})
public class Product {
    @Id
    @ColumnDefault("uuidv7()")
    @Column(name = "id", nullable = false)
    private UUID id = UUID.randomUUID();

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @NotNull
    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Size(max = 255)
    @NotNull
    @Column(name = "name", nullable = false)
    private String name;

    @Size(max = 300)
    @NotNull
    @Column(name = "slug", nullable = false, length = 300)
    private String slug;

    @Column(name = "description", length = Integer.MAX_VALUE)
    private String description;

    @NotNull
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'DRAFT'")
    @Column(name = "status", nullable = false, length = 20)
    private ProductStatus status = ProductStatus.DRAFT;

    @NotNull
    @ColumnDefault("false")
    @Column(name = "auction_enable", nullable = false)
    private Boolean auctionEnable;

    @NotNull
    @ColumnDefault("false")
    @Column(name = "has_order", nullable = false)
    private Boolean hasOrder;

    @NotNull
    @ColumnDefault("false")
    @Column(name = "in_auction", nullable = false)
    private Boolean inAuction;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @OneToMany(mappedBy = "product")
    private Set<ProductImage> productImages = new LinkedHashSet<>();

    @OneToOne(mappedBy = "products")
    private ProductStat productStat;

    @OneToMany(mappedBy = "product")
    private Set<Skus> skuses = new LinkedHashSet<>();


}
