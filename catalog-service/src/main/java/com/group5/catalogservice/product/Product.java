package com.group5.catalogservice.product;

import com.group5.catalogservice.category.Category;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 300)
    private String slug;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "auction_enable", nullable = false)
    private Boolean auctionEnable;

    @Column(name = "has_order", nullable = false)
    private Boolean hasOrder;

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
}
