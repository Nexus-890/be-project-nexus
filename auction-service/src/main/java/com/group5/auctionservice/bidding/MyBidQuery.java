package com.group5.auctionservice.bidding;

import com.group5.auctionservice.auction.AuctionStatus;
import com.group5.auctionservice.bidding.dto.MyBidResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Reads local auction snapshots; no dependency on bid-writing or settlement implementations. */
@Service
@RequiredArgsConstructor
public class MyBidQuery {
    private final NamedParameterJdbcTemplate jdbc;

    // Latest bid and count share a snapshot even when a concurrent transaction changes the leader.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<MyBidResponse> mine(UUID bidder, int page, int size) {
        var pageable = PageRequest.of(page, size);
        var parameters = new MapSqlParameterSource("bidder", bidder)
                .addValue("limit", size).addValue("offset", pageable.getOffset());
        long total = jdbc.queryForObject("""
                SELECT count(DISTINCT b.auction_id)
                FROM bids b JOIN auctions a ON a.id = b.auction_id
                WHERE b.bidder_id = :bidder AND a.deleted_at IS NULL
                    AND b.status NOT IN ('QUEUED', 'REJECTED')
                """, parameters, Long.class);
        var content = jdbc.query("""
                WITH latest AS (
                    SELECT DISTINCT ON (b.auction_id) b.auction_id, b.amount, b.placed_at, b.id
                    FROM bids b JOIN auctions a ON a.id = b.auction_id
                    WHERE b.bidder_id = :bidder AND a.deleted_at IS NULL
                    AND b.status NOT IN ('QUEUED', 'REJECTED')
                    ORDER BY b.auction_id, b.placed_at DESC, b.id DESC
                )
                SELECT a.id, a.title, latest.amount, latest.placed_at, a.status::text AS auction_status,
                    (a.status IN ('ACTIVE', 'ENDED') AND a.leader_bidder_id = :bidder AND EXISTS (
                        SELECT 1 FROM bids top_bid WHERE top_bid.auction_id = a.id
                        AND top_bid.bidder_id = :bidder AND top_bid.status IN ('PLACED', 'OUTBID', 'WON')
                        AND top_bid.amount = a.current_price)) AS leading,
                    (a.status IN ('WON', 'SETTLED', 'PAYMENT_TIMEOUT') AND a.winner_id = :bidder) AS won,
                    CASE WHEN a.status IN ('WON', 'SETTLED', 'PAYMENT_TIMEOUT') AND a.winner_id = :bidder
                        THEN a.order_id END AS order_id,
                    CASE WHEN a.status IN ('WON', 'SETTLED', 'PAYMENT_TIMEOUT') AND a.winner_id = :bidder
                        THEN a.payment_due_at END AS payment_due_at,
                    CASE WHEN a.status IN ('WON', 'SETTLED', 'PAYMENT_TIMEOUT') AND a.winner_id = :bidder
                        THEN a.final_price END AS final_price
                FROM latest JOIN auctions a ON a.id = latest.auction_id
                ORDER BY latest.placed_at DESC, latest.id DESC
                LIMIT :limit OFFSET :offset
                """, parameters, (row, number) -> new MyBidResponse(
                row.getObject("id", UUID.class), row.getString("title"), row.getBigDecimal("amount"),
                row.getTimestamp("placed_at").toInstant(), AuctionStatus.valueOf(row.getString("auction_status")),
                row.getBoolean("leading"), row.getBoolean("won"), row.getObject("order_id", UUID.class),
                row.getTimestamp("payment_due_at") == null ? null : row.getTimestamp("payment_due_at").toInstant(),
                row.getBigDecimal("final_price")));
        return new PageImpl<>(content, pageable, total);
    }
}
