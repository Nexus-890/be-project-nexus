package com.group5.auctionservice.offer;

import com.group5.auctionservice.auction.*;
import com.group5.auctionservice.reputation.*;
import com.group5.auctionservice.shared.config.AuctionProperties;
import com.group5.auctionservice.shared.exception.AuctionErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_events.*;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.group5.auctionservice.shared.exception.AuctionErrorCode.*;

@Service
@RequiredArgsConstructor
public class SecondChanceOfferService {
    private final AuctionRepository auctions;
    private final AuctionStateMachine states;
    private final AuctionProperties properties;
    private final ReputationGuard eligibility;
    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final EventEnvelopeFactory envelopes;
    private final PlatformTransactionManager transactions;

    public record Candidate(UUID bidderId, String displayName, BigDecimal amount) { }
    public record Offer(UUID id, UUID auctionId, UUID bidderId, String displayName, BigDecimal amount,
                        String status, boolean stockReady, Instant offeredAt, Instant expiresAt,
                        Instant respondedAt, UUID orderId, Instant paymentDueAt,
                        String title, String imageUrl, String currency) { }
    public record SellerState(UUID auctionId, boolean canOffer, Candidate candidate, List<Offer> offers) { }

    @Transactional(readOnly = true)
    public SellerState seller(UUID id, CurrentUser seller) {
        Auction auction = find(id, false);
        owner(auction, seller);
        return state(auction);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SellerState send(UUID id, CurrentUser seller) {
        Auction snapshot = find(id, false);
        requireSendable(snapshot, seller);
        List<Candidate> candidates = candidates(snapshot);
        // Rating may involve several HTTP calls. Do this without an auction lock or DB transaction.
        Candidate next = eligibleCandidate(candidates);
        if (next == null) throw error(OFFER_NO_ELIGIBLE_BIDDER);
        return new TransactionTemplate(transactions).execute(ignored -> {
            Auction auction = find(id, true);
            requireSendable(auction, seller);
            // Another request may have completed an offer round while eligibility was checked.
            // Re-read the local ranking/exclusions under the lock; never send a stale candidate.
            if (auction.getVersion() != snapshot.getVersion() || !candidates.equals(candidates(auction))) {
                throw error(OFFER_CANDIDATE_CHANGED);
            }
            return createOffer(auction, seller, next);
        });
    }

    private void requireSendable(Auction auction, CurrentUser seller) {
        owner(auction, seller);
        if (auction.getStatus() != AuctionStatus.PAYMENT_TIMEOUT) throw error(OFFER_AUCTION_NOT_TIMED_OUT);
        if (hasOpen(auction.getId())) throw error(OFFER_ALREADY_OPEN);
    }

    private SellerState createOffer(Auction auction, CurrentUser seller, Candidate next) {
        Instant now = now();
        UUID offer = UUID.randomUUID();
        Instant expires = now.plus(properties.getSecondChanceOfferHours(), ChronoUnit.HOURS);
        jdbc.update("""
                INSERT INTO auction_offers(id,auction_id,bidder_id,bidder_name,amount,status,offered_at,expires_at,created_by,updated_by)
                VALUES (?,?,?,?,?,'OFFERED',?,?,?,?)
                """, offer, auction.getId(), next.bidderId(), next.displayName(), next.amount(), ts(now), ts(expires), seller.id(), seller.id());
        var payload = payload(auction, offer, next.bidderId());
        payload.put("amount", next.amount().setScale(2).toPlainString());
        payload.put("currency", auction.getCurrency());
        payload.put("skuId", auction.getSkuId());
        payload.put("expiresAt", expires);
        emit("SecondChanceOffered", auction.getId(), payload);
        return state(auction);
    }

    @Transactional(readOnly = true)
    public Page<Offer> mine(UUID buyer, int page, int size) {
        var rows = jdbc.query(select() + " WHERE o.bidder_id=? ORDER BY o.offered_at DESC,o.id DESC LIMIT ? OFFSET ?",
                this::row, buyer, size, Math.multiplyExact((long) page, size));
        long total = jdbc.queryForObject("SELECT count(*) FROM auction_offers WHERE bidder_id=?", Long.class, buyer);
        return new PageImpl<>(rows, PageRequest.of(page, size), total);
    }

    @Transactional
    public Offer respond(UUID id, CurrentUser buyer, boolean accept) {
        Offer before = get(id);
        if (!before.bidderId().equals(buyer.id())) throw error(OFFER_NOT_YOURS);
        Auction auction = find(before.auctionId(), true);
        Offer offer = get(id); // Read again after serializing every operation on the auction row.
        if (!"OFFERED".equals(offer.status())) throw error(OFFER_ALREADY_RESPONDED);
        Instant now = now();
        if (!now.isBefore(offer.expiresAt())) throw error(OFFER_EXPIRED);
        if (accept && !offer.stockReady()) throw error(OFFER_STOCK_PENDING);
        if (auction.getStatus() != AuctionStatus.PAYMENT_TIMEOUT) throw error(OFFER_AUCTION_NOT_TIMED_OUT);
        Instant deadline = accept ? now.plus(properties.getPaymentDeadlineHours(), ChronoUnit.HOURS) : null;
        jdbc.update("""
                UPDATE auction_offers SET status=?,responded_at=?,payment_due_at=?,updated_by=? WHERE id=?
                """, accept ? "ACCEPTED" : "DECLINED", ts(now), ts(deadline), buyer.id(), id);
        if (accept) {
            auction.beginOffer(id, buyer.id(), offer.amount(), deadline);
            states.transition(auction, AuctionStatus.WON);
            auctions.saveAndFlush(auction);
            emit("AuctionWon", auction.getId(), new AuctionWon(auction.getId(), auction.getSellerId(),
                    auction.getProductId(), auction.getSkuId(), buyer.id(), offer.amount().setScale(2).toPlainString(),
                    auction.getCurrency(), auction.getTitle(), auction.getImageUrl(), 1,
                    auction.getEndAt(), deadline, id));
            emit("SecondChanceAccepted", auction.getId(), payload(auction, id, buyer.id()));
        } else emit("SecondChanceDeclined", auction.getId(), payload(auction, id, buyer.id()));
        return get(id);
    }

    @Transactional
    public void expire(UUID id) {
        Offer offer = get(id);
        Auction auction = find(offer.auctionId(), true);
        offer = get(id);
        if (!"OFFERED".equals(offer.status()) || now().isBefore(offer.expiresAt())) return;
        jdbc.update("UPDATE auction_offers SET status='EXPIRED',updated_by=? WHERE id=?", new UUID(0,0), id);
        emit("SecondChanceExpired", auction.getId(), payload(auction, id, offer.bidderId()));
    }

    public List<UUID> due() {
        return jdbc.queryForList("SELECT id FROM auction_offers WHERE status='OFFERED' AND expires_at<=clock_timestamp() ORDER BY expires_at LIMIT 100", UUID.class);
    }

    @Transactional
    public void stock(UUID id, UUID auctionId, boolean ready) {
        Auction auction = find(auctionId, true);
        Offer offer = get(id);
        if (!offer.auctionId().equals(auctionId)) throw new IllegalArgumentException("Offer stock identity mismatch");
        if (!"OFFERED".equals(offer.status())) return;
        if (ready && !offer.stockReady()) {
            if (!now().isBefore(offer.expiresAt())) { expire(id); return; }
            jdbc.update("UPDATE auction_offers SET stock_ready=true WHERE id=?", id);
            var payload = payload(auction,id,offer.bidderId());
            payload.put("amount",offer.amount().setScale(2).toPlainString());
            payload.put("currency",auction.getCurrency());
            payload.put("expiresAt",offer.expiresAt());
            emit("SecondChanceOfferReady",auctionId,payload);
        }
        else if (ready) return;
        else {
            jdbc.update("UPDATE auction_offers SET status='EXPIRED',updated_by=? WHERE id=?", new UUID(0,0), id);
            var payload = payload(auction, id, offer.bidderId());
            payload.put("reason", "STOCK_UNAVAILABLE");
            emit("SecondChanceExpired", auctionId, payload);
        }
    }

    private SellerState state(Auction auction) {
        List<Offer> history = jdbc.query(select()+" WHERE o.auction_id=? ORDER BY o.offered_at DESC,o.id DESC", this::row, auction.getId());
        Candidate candidate = null;
        if (auction.getStatus() == AuctionStatus.PAYMENT_TIMEOUT && !hasOpen(auction.getId())) {
            try { candidate = eligibleCandidate(candidates(auction)); }
            catch (DomainException failure) {
                // A preview outage must not hide historical offers. Writes still fail closed.
                if (!AUCTION_REPUTATION_UNAVAILABLE.code().equals(failure.getErrorCode())) throw failure;
            }
        }
        return new SellerState(auction.getId(), candidate != null, candidate, history);
    }

    private List<Candidate> candidates(Auction auction) {
        // The DISTINCT row preserves the masked name and a stable tie break for the bidder's highest bid.
        return jdbc.query("""
                SELECT bidder_id,bidder_name,amount FROM (
                  SELECT DISTINCT ON (b.bidder_id) b.bidder_id,b.bidder_name,b.amount,b.placed_at,b.id
                  FROM bids b WHERE b.auction_id=? AND b.status<>'CANCELLED' AND b.bidder_id<>?
                    AND b.bidder_id<>? AND NOT EXISTS(SELECT 1 FROM auction_offers o WHERE o.auction_id=b.auction_id AND o.bidder_id=b.bidder_id)
                    AND NOT EXISTS(SELECT 1 FROM auction_payment_timeouts t WHERE t.auction_id=b.auction_id AND t.winner_id=b.bidder_id)
                  ORDER BY b.bidder_id,b.amount DESC,b.placed_at,b.id
                ) ranked ORDER BY amount DESC,placed_at,id
                """, (rs,n)->new Candidate(rs.getObject(1,UUID.class), rs.getString(2),rs.getBigDecimal(3)),
                auction.getId(), auction.getWinnerId(), auction.getSellerId());
    }

    private Candidate eligibleCandidate(List<Candidate> candidates) {
        for (Candidate candidate : candidates) {
            try { eligibility.requireEligible(candidate.bidderId(), EligibilityAction.BID); return candidate; }
            catch (DomainException failure) {
                if (!AUCTION_REPUTATION_TOO_LOW.code().equals(failure.getErrorCode())) throw failure;
            }
        }
        return null;
    }

    private Auction find(UUID id, boolean lock) {
        return (lock ? auctions.findForUpdateByIdAndDeletedAtIsNull(id) : auctions.findByIdAndDeletedAtIsNull(id))
                .orElseThrow(()->error(AUCTION_NOT_FOUND));
    }
    private void owner(Auction auction, CurrentUser seller) {
        if (!seller.hasRole("SELLER") || !auction.getSellerId().equals(seller.id())) throw error(AUCTION_FORBIDDEN);
    }
    private boolean hasOpen(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM auction_offers WHERE auction_id=? AND status='OFFERED')", Boolean.class, id));
    }
    private Offer get(UUID id) {
        return jdbc.query(select()+" WHERE o.id=?",this::row,id).stream().findFirst().orElseThrow(()->error(OFFER_NOT_FOUND));
    }
    private static String select() { return "SELECT o.*,a.title,a.image_url,a.currency FROM auction_offers o JOIN auctions a ON a.id=o.auction_id"; }
    private Offer row(ResultSet r, int n) throws SQLException {
        return new Offer(r.getObject("id",UUID.class),r.getObject("auction_id",UUID.class),r.getObject("bidder_id",UUID.class),
                r.getString("bidder_name"),r.getBigDecimal("amount"),r.getString("status"),r.getBoolean("stock_ready"),
                instant(r,"offered_at"),instant(r,"expires_at"),instant(r,"responded_at"),r.getObject("order_id",UUID.class),
                instant(r,"payment_due_at"),r.getString("title"),r.getString("image_url"),r.getString("currency"));
    }
    private Map<String,Object> payload(Auction a, UUID offer, UUID bidder) {
        var payload = new LinkedHashMap<String,Object>();
        payload.put("offerId",offer); payload.put("auctionId",a.getId());
        payload.put("sellerId",a.getSellerId()); payload.put("bidderId",bidder); return payload;
    }
    private void emit(String type, UUID auction, Object payload) {
        outbox.write("Auction", Topics.AUCTION,envelopes.create(type,auction.toString(),payload));
    }
    private static Instant instant(ResultSet r,String name) throws SQLException { Timestamp ts=r.getTimestamp(name); return ts==null?null:ts.toInstant(); }
    private static Timestamp ts(Instant value) { return value==null?null:Timestamp.from(value); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static DomainException error(AuctionErrorCode code) { return new DomainException(code); }
}
