package com.group5.auctionservice.auction;

import com.group5.auctionservice.auction.dto.AuctionResponse;
import com.group5.auctionservice.auction.dto.CreateAuctionRequest;
import com.group5.auctionservice.shared.config.AuctionProperties;
import com.group5.auctionservice.shared.exception.AuctionErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AuctionService {

    private final AuctionRepository auctions;
    private final AuctionProperties properties;

    @Transactional
    public AuctionResponse create(CreateAuctionRequest request, CurrentUser seller) {
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
}
