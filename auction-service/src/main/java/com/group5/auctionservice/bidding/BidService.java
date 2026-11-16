package com.group5.auctionservice.bidding;

import com.group5.auctionservice.auction.Auction;
import com.group5.auctionservice.auction.AuctionRepository;
import com.group5.auctionservice.auction.AuctionStatus;
import com.group5.auctionservice.bidding.dto.BidResponse;
import com.group5.auctionservice.bidding.dto.BidStatusResponse;
import com.group5.auctionservice.bidding.dto.PlaceBidRequest;
import com.group5.auctionservice.shared.exception.AuctionErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;

import lombok.RequiredArgsConstructor;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BidService {
    private final AuctionRepository auctions;
    private final BidRepository bids;
    private final OutboxWriter outbox;
    private final EventEnvelopeFactory envelopes;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;
    private final AntiSnipingPolicy antiSnipingPolicy;

    public record PlacementResult(boolean created, BidResponse response) {}

    /** Chỉ nhận yêu cầu: không tranh row lock giá với các bidder khác, luôn trả biên nhận 202. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PlacementResult place(
            UUID auctionId, String key, PlaceBidRequest request, CurrentUser bidder) {
        if (key == null || key.isBlank())
            throw new DomainException(AuctionErrorCode.AUCTION_IDEMPOTENCY_KEY_REQUIRED);
        if (!key.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new DomainException(AuctionErrorCode.AUCTION_INVALID_IDEMPOTENCY_KEY);
        }
        String canonicalKey = UUID.fromString(key).toString();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try {
            return transaction.execute(status -> accept(auctionId, canonicalKey, request, bidder));
        } catch (DataIntegrityViolationException violation) {
            if (!isIdempotencyKeyViolation(violation)) throw violation;
            // UNIQUE loser phải rollback trước khi đọc lại biên nhận của transaction đã thắng.
            transaction.setReadOnly(true);
            return transaction.execute(
                    status ->
                            bids.findByIdempotencyKey(canonicalKey)
                                    .map(bid -> replay(auctionId, bidder, request.amount(), bid))
                                    .orElseThrow(() -> violation));
        }
    }

    /** Khóa advisory dùng chung chỉ chặn lifecycle kết thúc, không chặn các POST song song. */
    private PlacementResult accept(
            UUID auctionId, String key, PlaceBidRequest request, CurrentUser bidder) {
        Optional<Bid> previous = bids.findByIdempotencyKey(key);
        if (previous.isPresent())
            return replay(auctionId, bidder, request.amount(), previous.get());
        auctions.lockBidAdmission(auctionId);
        Instant requestedAt = auctions.currentWallTime();
        Auction auction =
                auctions.findAvailableById(auctionId)
                        .orElseThrow(() -> new DomainException(AuctionErrorCode.AUCTION_NOT_FOUND));
        if (auction.getSellerId().equals(bidder.id()))
            throw new DomainException(AuctionErrorCode.AUCTION_SELLER_CANNOT_BID);
        if (auction.getStatus() != AuctionStatus.ACTIVE
                || auction.getStartAt().isAfter(requestedAt)
                || !auction.getEndAt().isAfter(requestedAt))
            throw new DomainException(AuctionErrorCode.AUCTION_NOT_ACTIVE);
        Bid bid =
                bids.saveAndFlush(
                        Bid.queued(
                                auctionId,
                                bidder.id(),
                                request.amount().setScale(2),
                                requestedAt,
                                key,
                                BidderNameMask.mask(bidder.displayName())));
        outbox.write(
                "Auction",
                Topics.AUCTION_BIDS,
                envelopes.create(
                        "BidRequested",
                        auctionId.toString(),
                        new BidCommand(auctionId, bid.getId(), requestedAt)));
        return new PlacementResult(true, receipt(bid));
    }

    /**
     * Chờ cohort admission đã lấy requestedAt commit trước khi bắt đầu transaction xử lý. Listener
     * gọi trước IdempotentConsumer nên chỉ cần một connection; khóa được nhả ngay, cho POST mới lưu
     * QUEUED song song trong lúc consumer cập nhật giá của các bid cũ. Request đến sau barrier có
     * requestedAt sau command hiện tại nên không thể vượt mặt nó.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void awaitAdmissions(UUID auctionId) {
        auctions.lockBidProcessingAdmission(auctionId);
    }

    /** Consumer duy nhất theo auction xử lý các yêu cầu đã commit theo requestedAt rồi id. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(UUID auctionId, UUID bidId) {
        // Lấy advisory trước bid row lock để hai consumer khi rebalance không tạo deadlock.
        bids.lockProcessing(auctionId);
        Bid trigger =
                bids.findById(bidId)
                        .filter(bid -> bid.getAuctionId().equals(auctionId))
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Queued bid not found for command"));
        if (trigger.getStatus() != Bid.Status.QUEUED) return;
        // Relay nhiều instance có thể chuyển command sau tới trước: drain các row cũ đã commit
        // trước nó.
        for (Bid bid : bids.lockQueuedThrough(auctionId, trigger.getRequestedAt(), bidId))
            applyOne(bid);
    }

    /**
     * Giữ conditional UPDATE và anti-sniping cùng transaction; thời gian xét là lúc POST được nhận.
     */
    private void applyOne(Bid bid) {
        Instant processedAt = auctions.currentWallTime();
        if (auctions.acceptQueuedBid(
                        bid.getAuctionId(),
                        bid.getBidderId(),
                        bid.getAmount(),
                        bid.getBidderName(),
                        bid.getRequestedAt())
                == 0) {
            Auction auction = auctions.findAvailableById(bid.getAuctionId()).orElse(null);
            if (auction == null
                    || auction.getStatus() != AuctionStatus.ACTIVE
                    || auction.getStartAt().isAfter(bid.getRequestedAt())
                    || !auction.getEndAt().isAfter(bid.getRequestedAt())) {
                bid.rejected("AUCTION_NOT_ACTIVE", null, processedAt);
            } else if (auction.getSellerId().equals(bid.getBidderId())) {
                bid.rejected("AUCTION_SELLER_CANNOT_BID", null, processedAt);
            } else {
                bid.rejected(
                        "AUCTION_BID_TOO_LOW",
                        auction.getCurrentPrice().add(auction.getBidIncrement()),
                        processedAt);
            }
            bids.save(bid);
            return;
        }
        antiSnipingPolicy.extendAcceptedBid(
                bid.getAuctionId(), bid.getBidderId(), bid.getRequestedAt());
        bid.placed(processedAt);
        bids.save(bid);
        outbox.write(
                "Auction",
                Topics.AUCTION,
                envelopes.create(
                        "BidPlaced",
                        bid.getAuctionId().toString(),
                        new BidPlaced(
                                bid.getAuctionId(),
                                bid.getId(),
                                bid.getBidderId(),
                                bid.getAmount().toPlainString(),
                                "VND",
                                bid.getPlacedAt())));
    }

    /** Chỉ chủ yêu cầu đọc được kết quả; id sai, auction sai hoặc người khác đều trả cùng 404. */
    @Transactional(readOnly = true)
    public BidStatusResponse status(UUID auctionId, UUID bidId, CurrentUser bidder) {
        Bid bid =
                bids.findByIdAndAuctionIdAndBidderId(bidId, auctionId, bidder.id())
                        .orElseThrow(
                                () -> new DomainException(AuctionErrorCode.AUCTION_BID_NOT_FOUND));
        return new BidStatusResponse(
                bid.getId(),
                bid.getStatus().name(),
                bid.getRequestedAt(),
                bid.getAmount(),
                bid.getResultCurrentPrice(),
                bid.getResultLeaderBidderId(),
                bid.getRejectReason(),
                bid.getRejectMinimum());
    }

    /**
     * Cùng key chỉ replay cùng auction, bidder và amount; giữ nguyên biên nhận cả sau khi xử lý.
     */
    private PlacementResult replay(UUID auctionId, CurrentUser bidder, BigDecimal amount, Bid bid) {
        if (!bid.getAuctionId().equals(auctionId)
                || !bid.getBidderId().equals(bidder.id())
                || bid.getAmount().compareTo(amount) != 0)
            throw new DomainException(AuctionErrorCode.AUCTION_IDEMPOTENCY_KEY_CONFLICT);
        return new PlacementResult(false, receipt(bid));
    }

    /** Biên nhận POST không đổi khi bid đã PLACED/REJECTED; client theo dõi qua GET. */
    private BidResponse receipt(Bid bid) {
        return new BidResponse(bid.getId(), "QUEUED", bid.getRequestedAt());
    }

    /** Chỉ xử lý UNIQUE của Idempotency-Key; không che lỗi toàn vẹn dữ liệu khác. */
    private boolean isIdempotencyKeyViolation(DataIntegrityViolationException violation) {
        for (Throwable cause = violation; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint
                    && "23505".equals(constraint.getSQLState())
                    && "bids_idempotency_key_key".equals(constraint.getConstraintName()))
                return true;
        }
        return false;
    }

    private record BidPlaced(
            UUID auctionId,
            UUID bidId,
            UUID bidderId,
            String amount,
            String currency,
            Instant placedAt) {}
}
