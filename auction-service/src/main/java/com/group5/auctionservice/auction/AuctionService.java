package com.group5.auctionservice.auction;

import com.group5.auctionservice.auction.dto.AuctionResponse;
import com.group5.auctionservice.auction.dto.CreateAuctionRequest;
import com.group5.auctionservice.shared.config.AuctionProperties;
import com.group5.auctionservice.shared.exception.AuctionErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuctionService {

    private static final Duration START_TIME_TOLERANCE = Duration.ofSeconds(5);

    private final AuctionRepository auctions;
    private final AuctionProperties properties;
    private final Clock clock;
    private final AuctionStateMachine stateMachine;

    @Transactional(readOnly = true)
    public AuctionResponse get(UUID id) {
        return auctions.findByIdAndVisibilityAndDeletedAtIsNull(id, Auction.Visibility.PUBLIC)
                .map(AuctionResponse::from)
                .orElseThrow(() -> new DomainException(AuctionErrorCode.AUCTION_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Page<AuctionResponse> list(AuctionStatus status, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by("endAt").ascending().and(Sort.by("id")));
        if (status == AuctionStatus.CREATED || status == AuctionStatus.CANCELLED) {
            return Page.empty(pageable);
        }
        return auctions.findByVisibilityAndStatusAndDeletedAtIsNull(Auction.Visibility.PUBLIC, status, pageable)
                .map(AuctionResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<AuctionResponse> mine(AuctionStatus status, int page, int size, CurrentUser seller) {
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending().and(Sort.by("id").descending()));
        return (status == null ? auctions.findBySellerIdAndDeletedAtIsNull(seller.id(), pageable)
                : auctions.findBySellerIdAndStatusAndDeletedAtIsNull(seller.id(), status, pageable))
                .map(AuctionResponse::from);
    }

    @Transactional
    public AuctionResponse create(CreateAuctionRequest request, CurrentUser seller) {
        if (request.startAt().isBefore(Instant.now(clock).minus(START_TIME_TOLERANCE))) {
            throw new DomainException(AuctionErrorCode.AUCTION_START_IN_PAST);
        }
        Duration duration = Duration.between(request.startAt(), request.endAt());
        if (duration.isZero() || duration.isNegative()) {
            throw new DomainException(AuctionErrorCode.AUCTION_INVALID_TIME_WINDOW);
        }
        if (duration.compareTo(Duration.ofMinutes(properties.minDurationMinutes())) < 0) {
            throw new DomainException(AuctionErrorCode.AUCTION_DURATION_TOO_SHORT,
                    "Auction duration must be at least %d minutes".formatted(properties.minDurationMinutes()));
        }
        if (duration.compareTo(Duration.ofHours(properties.maxDurationHours())) > 0) {
            throw new DomainException(AuctionErrorCode.AUCTION_DURATION_TOO_LONG,
                    "Auction duration must be at most %d hours".formatted(properties.maxDurationHours()));
        }

        BigDecimal increment = request.bidIncrement() == null
                ? properties.defaultBidIncrement()
                : request.bidIncrement();
        Auction auction = new Auction(request.productId(), request.skuId(), seller.id(), request.title(),
                request.startingPrice(), increment, request.startAt(), request.endAt());
        return AuctionResponse.from(auctions.save(auction));
    }

    @Transactional
    public AuctionResponse schedule(UUID id, CurrentUser seller) {
        Auction auction = auctions.findForUpdateByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new DomainException(AuctionErrorCode.AUCTION_NOT_FOUND));
        if (!auction.getSellerId().equals(seller.id())) {
            throw new DomainException(AuctionErrorCode.AUCTION_FORBIDDEN);
        }
        stateMachine.transition(auction, AuctionStatus.SCHEDULED);
        return AuctionResponse.from(auction);
    }
}
