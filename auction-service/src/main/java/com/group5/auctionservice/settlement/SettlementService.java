package com.group5.auctionservice.settlement;

import com.group5.auctionservice.auction.Auction;
import com.group5.auctionservice.auction.AuctionRepository;
import com.group5.auctionservice.auction.AuctionStateMachine;
import com.group5.auctionservice.auction.AuctionStatus;
import com.group5.auctionservice.bidding.Bid;
import com.group5.auctionservice.bidding.BidRepository;
import com.group5.auctionservice.shared.config.AuctionProperties;
import com.group5.common_events.AuctionWon;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementService {
    private final AuctionRepository auctions;
    private final BidRepository bids;
    private final AuctionStateMachine stateMachine;
    private final AuctionProperties properties;
    private final EventEnvelopeFactory envelopes;
    private final OutboxWriter outbox;

    @Transactional
    public void settle(UUID auctionId) {
        var candidate = auctions.findForUpdateByIdAndDeletedAtIsNull(auctionId);
        if (candidate.isEmpty() || candidate.get().getStatus() != AuctionStatus.ENDED) {
            return;
        }
        Auction auction = candidate.get();
        // Hàng đợi chậm không được biến một bid trước deadline thành phiên không có người thắng.
        if (!auctions.tryCloseBidAdmission(auctionId) || auctions.hasEligibleQueuedBids(auctionId)) return;
        bids.findFirstByAuctionIdAndStatusInOrderByAmountDescPlacedAtAscIdAsc(auctionId,
                java.util.List.of(Bid.Status.PLACED, Bid.Status.OUTBID, Bid.Status.WON))
                .ifPresentOrElse(bid -> declareWinner(auction, bid), () -> failWithoutBids(auction));
    }

    private void declareWinner(Auction auction, Bid bid) {
        if (auction.getImageUrl() == null || auction.getImageUrl().isBlank()) {
            throw new IllegalStateException("Auction " + auction.getId() + " is missing its image snapshot");
        }
        auction.recordWinner(bid.getBidderId(), bid.getAmount(),
                auction.getEndAt().plus(properties.getPaymentDeadlineHours(), ChronoUnit.HOURS));
        stateMachine.transition(auction, AuctionStatus.WON);
        var payload = new AuctionWon(
                auction.getId(), auction.getSellerId(), auction.getProductId(), auction.getSkuId(),
                auction.getWinnerId(), auction.getFinalPrice().setScale(2).toPlainString(),
                auction.getCurrency(), auction.getTitle(), auction.getImageUrl(), 1,
                auction.getEndAt(), auction.getPaymentDueAt(), null, auction.getSkuCode());
        outbox.write("Auction", Topics.AUCTION, envelopes.create("AuctionWon", auction.getId().toString(), payload));
    }

    private void failWithoutBids(Auction auction) {
        stateMachine.transition(auction, AuctionStatus.FAILED);
        outbox.write("Auction", Topics.AUCTION, envelopes.createWithTimestamp(
                "AuctionFailed", auction.getId().toString(), failedAt -> Map.of(
                        "auctionId", auction.getId(),
                        "sellerId", auction.getSellerId(),
                        "productId", auction.getProductId(),
                        "skuId", auction.getSkuId(),
                        "reason", "NO_BIDS",
                        "endAt", auction.getEndAt(),
                        "failedAt", failedAt)));
    }
}
