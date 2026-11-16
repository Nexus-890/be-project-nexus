package com.group5.auctionservice.stream;

import com.group5.auctionservice.auction.Auction;
import com.group5.auctionservice.auction.AuctionRepository;
import com.group5.auctionservice.auction.AuctionStateQuery;
import com.group5.auctionservice.auction.AuctionStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Giữ lợi ích cache của polling nhưng event SSE phải thấy ngay version và deadline vừa commit. */
class AuctionStateVersionTest {
    /** Refresh bỏ snapshot cũ trước TTL một giây và trả version khớp toàn bộ body trạng thái. */
    @Test
    void eventRefreshReloadsPriceVersionAndDeadlineWithoutWaitingForCacheTtl() {
        var repository = mock(AuctionRepository.class);
        var auction = mock(Auction.class);
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-16T00:00:00Z");
        when(repository.findByIdAndVisibilityAndDeletedAtIsNull(id, Auction.Visibility.PUBLIC)).thenReturn(Optional.of(auction));
        when(auction.getCurrentPrice()).thenReturn(new BigDecimal("100"));
        when(auction.getBidIncrement()).thenReturn(BigDecimal.TEN);
        when(auction.getStatus()).thenReturn(AuctionStatus.ACTIVE);
        when(auction.getEndAt()).thenReturn(now.plusSeconds(10));
        when(auction.getVersion()).thenReturn(0L);
        var query = new AuctionStateQuery(repository, Clock.fixed(now, ZoneOffset.UTC));
        var initial = query.getVersioned(id);
        assertThat(initial.version()).isZero();
        when(auction.getCurrentPrice()).thenReturn(new BigDecimal("120"));
        when(auction.getBidCount()).thenReturn(1);
        when(auction.getEndAt()).thenReturn(now.plusSeconds(310));
        when(auction.getVersion()).thenReturn(2L);
        assertThat(query.getVersioned(id).state().currentPrice()).isEqualByComparingTo("100");
        verify(repository, times(1)).findByIdAndVisibilityAndDeletedAtIsNull(id, Auction.Visibility.PUBLIC);

        var refreshed = query.refreshVersioned(id);
        assertThat(refreshed.version()).isEqualTo(2L);
        assertThat(refreshed.state().currentPrice()).isEqualByComparingTo("120");
        assertThat(refreshed.state().endAt()).isEqualTo(now.plusSeconds(310));
        assertThat(refreshed.state().bidCount()).isEqualTo(1);
        assertThat(query.get(id)).isEqualTo(refreshed.state());
        verify(repository, times(2)).findByIdAndVisibilityAndDeletedAtIsNull(id, Auction.Visibility.PUBLIC);
    }
}