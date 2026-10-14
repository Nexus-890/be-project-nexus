package com.group5.catalogservice.product.entity;

import com.group5.catalogservice.category.Category;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @NotNull
    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Size(max = 255)
    @NotNull
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Size(max = 300)
    @NotNull
    @Column(name = "slug", nullable = false, length = 300)
    private String slug;

    @Column(name = "description", length = Integer.MAX_VALUE)
    private String description;

    @Size(max = 20)
    @NotNull
    @ColumnDefault("'DRAFT'")
    @Column(name = "status", nullable = false, length = 20)
    private String status;

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

    @NotNull
    @ColumnDefault("now()")
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @NotNull
    @ColumnDefault("now()")
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @OneToMany(mappedBy = "product", fetch = FetchType.LAZY)
    private Set<ProductImage> productImages = new LinkedHashSet<>();

    @OneToOne(mappedBy = "products1", fetch = FetchType.LAZY)
    private ProductStat productStat;

    @OneToMany(mappedBy = "product", fetch = FetchType.LAZY)
    private Set<Skus> skuses = new LinkedHashSet<>();

}