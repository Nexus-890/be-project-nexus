package com.group5.auctionservice.auction;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.group5.auctionservice.auction.dto.AuctionStateResponse;
import com.group5.auctionservice.shared.exception.AuctionErrorCode;
import com.group5.common_core.exception.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class AuctionStateQuery {
    private final AuctionRepository auctions;
    private final Clock clock;
    private final LoadingCache<UUID, Snapshot> snapshots;

    public AuctionStateQuery(AuctionRepository auctions, Clock clock) {
        this.auctions = auctions;
        this.clock = clock;
        this.snapshots = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(1))
                .build(id -> auctions.findByIdAndVisibilityAndDeletedAtIsNull(id, Auction.Visibility.PUBLIC)
                        .map(Snapshot::from)
                        .orElseThrow(() -> new DomainException(AuctionErrorCode.AUCTION_NOT_FOUND)));
    }

    public AuctionStateResponse get(UUID id) {
        return response(id, snapshots.get(id));
    }

    public AuctionStateResponse getManaged(UUID id) {
        return response(id, auctions.findByIdAndDeletedAtIsNull(id)
                .map(Snapshot::from)
                .orElseThrow(() -> new DomainException(AuctionErrorCode.AUCTION_NOT_FOUND)));
    }

    /** Lấy version cùng snapshot cache, giữ nguyên JSON công khai của GET /state. */
    public VersionedState getVersioned(UUID id) {
        Snapshot snapshot = snapshots.get(id);
        return new VersionedState(snapshot.version(), response(id, snapshot));
    }

    /** Event đã commit phải đọc mới ngay, không chờ TTL một giây làm trễ SSE và endAt anti-sniping. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public VersionedState refreshVersioned(UUID id) {
        snapshots.invalidate(id);
        return getVersioned(id);
    }

    public record VersionedState(long version, AuctionStateResponse state) { }

    private AuctionStateResponse response(UUID id, Snapshot snapshot) {
        return new AuctionStateResponse(id, snapshot.currentPrice(), snapshot.bidCount(), snapshot.bidIncrement(),
                snapshot.leaderBidderName(), snapshot.endAt(), snapshot.status(), clock.instant());
    }

    private record Snapshot(BigDecimal currentPrice, int bidCount, BigDecimal bidIncrement,
                            String leaderBidderName, Instant endAt, AuctionStatus status, long version) {
        static Snapshot from(Auction auction) {
            return new Snapshot(auction.getCurrentPrice(), auction.getBidCount(), auction.getBidIncrement(),
                    auction.getLeaderBidderName(), auction.getEndAt(), auction.getStatus(), auction.getVersion());
        }
    }
}
