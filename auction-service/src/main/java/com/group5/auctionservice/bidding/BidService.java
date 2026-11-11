package com.group5.auctionservice.bidding;

import com.group5.auctionservice.auction.Auction;
import com.group5.auctionservice.auction.AuctionRepository;
import com.group5.auctionservice.auction.AuctionStatus;
import com.group5.auctionservice.bidding.dto.BidResponse;
import com.group5.auctionservice.bidding.dto.PlaceBidRequest;
import com.group5.auctionservice.shared.exception.AuctionErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BidService {

    private final AuctionRepository auctions;
    private final BidRepository bids;
    private final OutboxWriter outbox;
    private final EventEnvelopeFactory envelopes;
    private final Clock clock;
    private final AntiSnipingPolicy antiSnipingPolicy;

    @Transactional
    public BidResponse place(UUID auctionId, PlaceBidRequest request, CurrentUser bidder) {
        BigDecimal amount = request.amount().setScale(2);
        if (auctions.acceptBid(auctionId, bidder.id(), amount) == 0) {
            Auction auction = auctions.findAvailableById(auctionId)
                    .orElseThrow(() -> new DomainException(AuctionErrorCode.AUCTION_NOT_FOUND));
            if (auction.getSellerId().equals(bidder.id())) {
                throw new DomainException(AuctionErrorCode.AUCTION_SELLER_CANNOT_BID);
            }
            if (auction.getStatus() != AuctionStatus.ACTIVE) {
                throw new DomainException(AuctionErrorCode.AUCTION_NOT_ACTIVE);
            }
            // Scheduler có thể chưa đổi trạng thái; kiểm tra cửa sổ bid theo thời gian của DB.
            Instant now = auctions.currentTransactionTime();
            if (auction.getStartAt().isAfter(now) || !auction.getEndAt().isAfter(now)) {
                throw new DomainException(AuctionErrorCode.AUCTION_NOT_ACTIVE);
            }
            BigDecimal minimum = auction.getCurrentPrice().add(auction.getBidIncrement());
            throw new DomainException(AuctionErrorCode.AUCTION_BID_TOO_LOW,
                    "Bid amount must be at least " + minimum.toPlainString(), Map.of("minimum", minimum));
        }

        Bid bid = bids.saveAndFlush(new Bid(auctionId, bidder.id(), amount,
                Instant.now(clock).truncatedTo(ChronoUnit.MICROS)));

        // Cùng transaction và row lock với bid; lỗi sau đây rollback cả deadline lẫn bid.
        antiSnipingPolicy.extendAcceptedBid(auctionId, bidder.id());

        BidPlaced payload = new BidPlaced(auctionId, bid.getId(), bidder.id(), amount.toPlainString(),
                "VND", bid.getPlacedAt());
        outbox.write("Auction", Topics.AUCTION, envelopes.create("BidPlaced", auctionId.toString(), payload));
        return new BidResponse(bid.getId(), amount, bidder.id());
    }

    private record BidPlaced(UUID auctionId, UUID bidId, UUID bidderId, String amount,
                             String currency, Instant placedAt) {
    }
}
