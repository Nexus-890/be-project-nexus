package com.group5.inventoryservice.reservation.service;

import com.group5.common_events.*;
import com.group5.common_outbox.OutboxWriter;
import com.group5.inventoryservice.inventory.*;
import com.group5.inventoryservice.reservation.*;
import com.group5.inventoryservice.reservation.entity.InventoryReservation;
import com.group5.inventoryservice.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class OfferReservationService {
    private static final UUID SYSTEM = new UUID(0,0);
    private final JdbcTemplate jdbc;
    private final ReservationRepository reservations;
    private final InventoryBalanceRepository balances;
    private final InventoryMovementRepository movements;
    private final WarehouseResolver warehouses;
    private final OutboxWriter outbox;
    private final EventEnvelopeFactory envelopes;
    private final ReservationService orderOutcomes;

    @Transactional
    public void hold(UUID auction, UUID offer, UUID bidder, UUID sku, Instant expires) {
        lock(auction);
        if (terminal(offer)) return;
        var existing=reservations.findForUpdateByOfferId(offer);
        if (existing.isPresent()) {
            requireIdentity(existing.get(),auction,bidder);
            return;
        }
        // Commerce cancellation and auction events have independent topics. Let the order listener
        // release the previous round before reserving this round; never reuse its reservation row.
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM inventory_reservations WHERE auction_id=? AND status='HELD')",Boolean.class,auction))) {
            jdbc.update("""
                    INSERT INTO inventory_pending_offer_holds(offer_id,auction_id,bidder_id,sku_id,expires_at)
                    VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING
                    """,offer,auction,bidder,sku,java.sql.Timestamp.from(expires));
            return;
        }
        UUID warehouse=warehouses.defaultWarehouseId();
        if (!Instant.now().isBefore(expires) || balances.reserve(sku,warehouse,1,SYSTEM)==0) {
            jdbc.update("INSERT INTO inventory_offer_outcomes(offer_id,auction_id,outcome) VALUES (?,?,'REJECTED') ON CONFLICT DO NOTHING",offer,auction);
            emit("SecondChanceStockRejected",auction,offer,Map.of("reason","STOCK_UNAVAILABLE"));
            return;
        }
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        var reservation=reservations.saveAndFlush(InventoryReservation.heldForOffer(auction,offer,bidder,sku,warehouse,now,expires.atOffset(ZoneOffset.UTC)));
        movements.record(sku,warehouse,MovementType.RESERVE,1,reservation.getId(),SYSTEM);
        emit("SecondChanceStockReady",auction,offer,Map.of("reservationId",reservation.getId()));
        jdbc.update("DELETE FROM inventory_pending_offer_holds WHERE offer_id=?",offer);
    }

    @Transactional
    public void release(UUID auction, UUID offer, String outcome) {
        lock(auction);
        jdbc.update("DELETE FROM inventory_pending_offer_holds WHERE offer_id=?",offer);
        jdbc.update("INSERT INTO inventory_offer_outcomes(offer_id,auction_id,outcome) VALUES (?,?,?) ON CONFLICT DO NOTHING",offer,auction,outcome);
        var existing=reservations.findForUpdateByOfferId(offer);
        if (existing.isEmpty()) return; // Tombstone prevents a delayed Offered from reserving stock.
        var hold=existing.get();
        if (!auction.equals(hold.getAuctionId())) throw new IllegalArgumentException("Offer hold auction mismatch");
        if (hold.getOrderId()!=null || reservations.releaseHeld(hold.getId(),SYSTEM)==0) return;
        if(balances.releaseReserved(hold.getSkuId(),hold.getWarehouseId(),hold.getQuantity(),SYSTEM)!=1)
            throw new IllegalStateException("Offer balance missing");
        movements.record(hold.getSkuId(),hold.getWarehouseId(),MovementType.RELEASE,hold.getQuantity(),hold.getId(),SYSTEM);
    }
    public List<UUID> pending() { return jdbc.queryForList("SELECT offer_id FROM inventory_pending_offer_holds ORDER BY expires_at LIMIT 100",UUID.class); }
    @Transactional
    public void retry(UUID offer) {
        var rows=jdbc.query("SELECT * FROM inventory_pending_offer_holds WHERE offer_id=?",(r,n)->new Pending(
                r.getObject("auction_id",UUID.class),r.getObject("bidder_id",UUID.class),r.getObject("sku_id",UUID.class),r.getTimestamp("expires_at").toInstant()),offer);
        if(rows.isEmpty())return;
        var row=rows.getFirst();
        if(terminal(offer)) { jdbc.update("DELETE FROM inventory_pending_offer_holds WHERE offer_id=?",offer); return; }
        hold(row.auction(),offer,row.bidder(),row.sku(),row.expires());
    }
    private record Pending(UUID auction,UUID bidder,UUID sku,Instant expires) { }

    @Transactional
    public void winner(UUID auction,UUID offer,UUID bidder) {
        lock(auction);
        var hold=reservations.findForUpdateByOfferId(offer).orElseThrow(()->new IllegalStateException("Offer stock is not held"));
        requireIdentity(hold,auction,bidder);
        // A replay after this round's cancellation must never affect a newer round.
    }

    @Transactional
    public void link(UUID auction,UUID offer,UUID order) {
        lock(auction);
        orderOutcomes.lockAuctionOrder(order);
        var hold=reservations.findForUpdateByOfferId(offer).orElseThrow(()->new IllegalStateException("Offer stock is not held"));
        if(!auction.equals(hold.getAuctionId())) throw new IllegalArgumentException("Offer hold auction mismatch");
        if(order.equals(hold.getOrderId())) return;
        if(hold.getStatus()!=ReservationStatus.HELD || hold.getOrderId()!=null) throw new IllegalStateException("Offer order conflict");
        hold.linkOrder(order);
        reservations.flush();
        orderOutcomes.applyAuctionOutcome(order);
    }
    private void lock(UUID auction) { jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('offer-hold:'||?::text,0))",Integer.class,auction); }
    private boolean terminal(UUID offer) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM inventory_offer_outcomes WHERE offer_id=?)",Boolean.class,offer)); }
    private void requireIdentity(InventoryReservation hold,UUID auction,UUID bidder) {
        if(!auction.equals(hold.getAuctionId()) || !bidder.equals(hold.getAuctionWinnerId())) throw new IllegalArgumentException("Offer hold identity mismatch");
    }
    private void emit(String type,UUID auction,UUID offer,Map<String,Object> extra) {
        var payload=new LinkedHashMap<String,Object>(extra); payload.put("auctionId",auction);payload.put("offerId",offer);
        outbox.write("Auction",Topics.INVENTORY,envelopes.create(type,auction.toString(),payload));
    }
}
