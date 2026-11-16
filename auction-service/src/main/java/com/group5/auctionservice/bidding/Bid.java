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

    @Column(nullable = false, updatable = false)
    private String bidderName;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "bid_status")
    private Status status = Status.PLACED;

    @Column(nullable = false, unique = true, length = 100, updatable = false)
    private String idempotencyKey;

    private Instant placedAt;

    @Column(nullable = false, updatable = false)
    private Instant requestedAt;

    private Instant processedAt;

    @Column(length = 80)
    private String rejectReason;

    @Column(precision = 19, scale = 2)
    private BigDecimal rejectMinimum;

    @Column(precision = 19, scale = 2)
    private BigDecimal resultCurrentPrice;

    private UUID resultLeaderBidderId;

    /** Giữ constructor của bid lịch sử và fixture; luồng POST mới dùng queued(). */
    Bid(
            UUID auctionId,
            UUID bidderId,
            BigDecimal amount,
            Instant placedAt,
            String idempotencyKey,
            String bidderName) {
        this.auctionId = auctionId;
        this.bidderId = bidderId;
        this.amount = amount;
        this.placedAt = placedAt;
        this.requestedAt = placedAt;
        this.processedAt = placedAt;
        this.idempotencyKey = idempotencyKey;
        this.bidderName = bidderName;
    }

    /** Lưu yêu cầu trước khi gửi command; chưa công bố đây là một bid đã thắng bước giá. */
    static Bid queued(
            UUID auctionId,
            UUID bidderId,
            BigDecimal amount,
            Instant requestedAt,
            String idempotencyKey,
            String bidderName) {
        Bid bid = new Bid(auctionId, bidderId, amount, requestedAt, idempotencyKey, bidderName);
        bid.status = Status.QUEUED;
        bid.placedAt = null;
        bid.processedAt = null;
        return bid;
    }

    /** Chốt snapshot PLACED cùng transaction cập nhật auction và ghi BidPlaced. */
    void placed(Instant processedAt) {
        this.status = Status.PLACED;
        this.placedAt = requestedAt;
        this.processedAt = processedAt;
        this.resultCurrentPrice = amount;
        this.resultLeaderBidderId = bidderId;
    }

    /** Ghi kết quả từ chối bền vững để retry command không đánh giá lại theo giá mới. */
    void rejected(String reason, BigDecimal minimum, Instant processedAt) {
        this.status = Status.REJECTED;
        this.rejectReason = reason;
        this.rejectMinimum = minimum;
        this.processedAt = processedAt;
    }

    public enum Status {
        QUEUED,
        PLACED,
        REJECTED,
        OUTBID,
        WON,
        CANCELLED
    }
}
