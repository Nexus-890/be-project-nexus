package com.group5.catalogservice.product.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "product_stats")
public class ProductStat {
    @Id
    @Column(name = "product_id", nullable = false)
    private UUID id;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "product_id", nullable = false)
    private Product products;

    @NotNull
    @ColumnDefault("0")
    @Column(name = "order_count", nullable = false)
    private Integer orderCount;

    @NotNull
    @ColumnDefault("0")
    @Column(name = "view_count", nullable = false)
    private Integer viewCount;

    @Column(name = "auction_end_at")
    private OffsetDateTime auctionEndAt;

    @NotNull
    @ColumnDefault("now()")
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;


}