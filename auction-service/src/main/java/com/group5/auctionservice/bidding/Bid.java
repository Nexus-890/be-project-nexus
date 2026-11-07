package com.group5.auctionservice.bidding;

import com.group5.common_core.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "bids")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Bid extends AuditedEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID auctionId;

    @Column(nullable = false, updatable = false)
    private UUID bidderId;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "bid_status")
    private Status status = Status.PLACED;

    @Column(nullable = false, unique = true, length = 100, updatable = false)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private Instant placedAt;

    Bid(UUID auctionId, UUID bidderId, BigDecimal amount, Instant placedAt) {
        this.auctionId = auctionId;
        this.bidderId = bidderId;
        this.amount = amount;
        this.placedAt = placedAt;
        // #147 will replace the internal key with the caller's Idempotency-Key and replay handling.
        this.idempotencyKey = UUID.randomUUID().toString();
    }

    public enum Status {
        PLACED, OUTBID, WON, CANCELLED
    }
}
