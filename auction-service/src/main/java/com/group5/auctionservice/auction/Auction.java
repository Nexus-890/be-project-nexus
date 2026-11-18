package com.group5.auctionservice.auction;

import com.group5.common_core.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "auctions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Auction extends AuditedEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private UUID skuId;

    @Column(nullable = false, updatable = false)
    private UUID sellerId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 500)
    private String imageUrl;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal startingPrice;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal bidIncrement;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal currentPrice;

    @Column(nullable = false)
    private int bidCount;

    private String leaderBidderName;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency = "VND";

    @Column(nullable = false)
    private Instant startAt;

    @Column(nullable = false)
    private Instant endAt;

    @Column(nullable = false)
    private Instant originalEndAt;

    @Column(nullable = false)
    private short extensionCount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "auction_visibility")
    private Visibility visibility = Visibility.PUBLIC;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "auction_status")
    private AuctionStatus status = AuctionStatus.CREATED;

    private UUID winnerId;

    @Column(precision = 19, scale = 2)
    private BigDecimal finalPrice;

    private Instant paymentDueAt;

    private UUID orderId;

    private Instant settledAt;

    @Column(name = "offer_id")
    private UUID offerId;

    public void beginOffer(UUID offerId, UUID bidderId, BigDecimal amount, Instant deadline) {
        if (status != AuctionStatus.PAYMENT_TIMEOUT) throw new IllegalStateException("Auction is not timed out");
        this.offerId = java.util.Objects.requireNonNull(offerId);
        this.orderId = null;
        this.settledAt = null;
        recordWinner(bidderId, amount, deadline);
    }

    public void recordSettlement(UUID orderId, Instant settledAt) {
        if (status != AuctionStatus.SETTLED || this.orderId != null) {
            throw new IllegalStateException("Auction must transition to SETTLED first");
        }
        this.orderId = java.util.Objects.requireNonNull(orderId);
        this.settledAt = java.util.Objects.requireNonNull(settledAt);
    }

    @Column(length = 20)
    private String cancelledBy;

    @Column(length = 255)
    private String cancelledReason;

    private UUID cancelledActorId;

    private Instant cancelledAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    private Instant deletedAt;

    Auction(UUID productId, UUID skuId, UUID sellerId, String title, String imageUrl, BigDecimal startingPrice,
            BigDecimal bidIncrement, Instant startAt, Instant endAt) {
        this.productId = productId;
        this.skuId = skuId;
        this.sellerId = sellerId;
        this.title = title;
        this.imageUrl = imageUrl;
        this.startingPrice = startingPrice;
        this.currentPrice = startingPrice;
        this.bidIncrement = bidIncrement;
        this.startAt = startAt;
        this.endAt = endAt;
        this.originalEndAt = endAt;
    }

    void setStatus(AuctionStatus status) {
        this.status = status;
    }

    public void recordWinner(UUID bidderId, BigDecimal price, Instant deadline) {
        this.winnerId = bidderId;
        this.finalPrice = price;
        this.paymentDueAt = deadline;
    }

    void configure(BigDecimal startingPrice, BigDecimal bidIncrement, Instant startAt, Instant endAt) {
        this.startingPrice = startingPrice;
        this.currentPrice = startingPrice;
        this.bidIncrement = bidIncrement;
        this.startAt = startAt;
        this.endAt = endAt;
        // Configuration changes the baseline; bidding extensions never call this method.
        this.originalEndAt = endAt;
    }

    void recordCancellation(String cancelledBy, UUID actorId, String reason, Instant cancelledAt) {
        this.cancelledBy = cancelledBy;
        this.cancelledActorId = actorId;
        this.cancelledReason = reason;
        this.cancelledAt = cancelledAt;
    }

    public enum Visibility {
        PUBLIC, RESTRICTED
    }
}
