package com.group5.auctionservice.auction;

import com.group5.auctionservice.auction.dto.AuctionResponse;
import com.group5.auctionservice.auction.dto.CreateAuctionRequest;
import com.group5.auctionservice.auction.dto.UpdateAuctionRequest;
import com.group5.auctionservice.auction.dto.CancelAuctionRequest;
import com.group5.auctionservice.reputation.EligibilityAction;
import com.group5.auctionservice.reputation.ReputationGuard;
import com.group5.auctionservice.shared.config.AuctionProperties;
import com.group5.auctionservice.bidding.BidRepository;
import com.group5.auctionservice.shared.exception.AuctionErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuctionService {

    private static final Duration START_TIME_TOLERANCE = Duration.ofSeconds(5);
    private static final UUID SYSTEM_ACTOR = new UUID(0, 0);

    private final AuctionRepository auctions;
    private final AuctionProperties properties;
    private final Clock clock;
    private final AuctionStateMachine stateMachine;
    private final AuctionCatalog catalog;
    private final AuctionInventoryClient inventory;
    private final AuctionCreation auctionCreation;
    private final OutboxWriter outbox;
    private final EventEnvelopeFactory envelopes;
    private final BidRepository bids;
    private final ReputationGuard reputation;

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
    public Page<AuctionResponse> manage(AuctionStatus status, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending().and(Sort.by("id").descending()));
        return (status == null ? auctions.findByDeletedAtIsNull(pageable)
                : auctions.findByStatusAndDeletedAtIsNull(status, pageable))
                .map(AuctionResponse::from);
    }

    @Transactional(readOnly = true)
    public AuctionResponse manageGet(UUID id) {
        return auctions.findByIdAndDeletedAtIsNull(id)
                .map(AuctionResponse::from)
                .orElseThrow(() -> new DomainException(AuctionErrorCode.AUCTION_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Page<AuctionResponse> mine(AuctionStatus status, int page, int size, CurrentUser seller) {
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending().and(Sort.by("id").descending()));
        return (status == null ? auctions.findBySellerIdAndDeletedAtIsNull(seller.id(), pageable)
                : auctions.findBySellerIdAndStatusAndDeletedAtIsNull(seller.id(), status, pageable))
                .map(AuctionResponse::from);
    }

    public AuctionResponse create(CreateAuctionRequest request, CurrentUser seller) {
        validateWindow(request.startAt(), request.endAt());
        CatalogClient.Product product = catalog.requireProduct(request.productId());
        if (!seller.id().equals(product.sellerId())) {
            throw new DomainException(AuctionErrorCode.AUCTION_FORBIDDEN, "The product belongs to another seller");
        }
        if (product.skus().stream().noneMatch(sku -> request.skuId().equals(sku.id()))) {
            throw new DomainException(AuctionErrorCode.AUCTION_SKU_NOT_FOUND);
        }
        String imageUrl = requireImage(product.firstImageUrl());
        requireStock(request.skuId());
        reputation.requireEligible(seller.id(), EligibilityAction.SELL);

        BigDecimal increment = request.bidIncrement() == null
                ? properties.getDefaultBidIncrement()
                : request.bidIncrement();
        Auction auction = new Auction(request.productId(), request.skuId(), seller.id(), product.name(),
                imageUrl, request.startingPrice(), increment, request.startAt(), request.endAt());
        return AuctionResponse.from(auctionCreation.save(auction));
    }

    private static String requireImage(String imageUrl) {
        if (imageUrl != null && !imageUrl.isBlank() && imageUrl.length() <= 500) {
            try {
                if (URI.create(imageUrl).isAbsolute()) {
                    return imageUrl;
                }
            } catch (IllegalArgumentException ignored) {
                // Catalog must provide an absolute URI usable in the AuctionWon snapshot.
            }
        }
        throw new DomainException(AuctionErrorCode.AUCTION_IMAGE_REQUIRED);
    }

    private void requireStock(UUID skuId) {
        AuctionInventoryClient.Stock stock;
        try {
            var response = inventory.available(skuId);
            stock = response == null ? null : response.data();
        } catch (feign.FeignException failure) {
            throw new DomainException(AuctionErrorCode.AUCTION_INVENTORY_UNAVAILABLE);
        }
        if (stock == null || !skuId.equals(stock.skuId()) || stock.available() == null || stock.available() < 0) {
            throw new DomainException(AuctionErrorCode.AUCTION_INVENTORY_UNAVAILABLE);
        }
        if (stock.available() == 0) {
            throw new DomainException(AuctionErrorCode.AUCTION_STOCK_UNAVAILABLE);
        }
    }

    private void validateWindow(Instant startAt, Instant endAt) {
        if (startAt.isBefore(Instant.now(clock).minus(START_TIME_TOLERANCE))) {
            throw new DomainException(AuctionErrorCode.AUCTION_START_IN_PAST);
        }
        Duration duration = Duration.between(startAt, endAt);
        if (duration.isZero() || duration.isNegative()) {
            throw new DomainException(AuctionErrorCode.AUCTION_INVALID_TIME_WINDOW);
        }
        if (duration.compareTo(Duration.ofMinutes(properties.getMinDurationMinutes())) < 0) {
            throw new DomainException(AuctionErrorCode.AUCTION_DURATION_TOO_SHORT,
                    "Auction duration must be at least %d minutes".formatted(properties.getMinDurationMinutes()));
        }
        if (duration.compareTo(Duration.ofHours(properties.getMaxDurationHours())) > 0) {
            throw new DomainException(AuctionErrorCode.AUCTION_DURATION_TOO_LONG,
                    "Auction duration must be at most %d hours".formatted(properties.getMaxDurationHours()));
        }
    }

    @Transactional
    public AuctionResponse update(UUID id, UpdateAuctionRequest request, CurrentUser actor) {
        Auction auction = lock(id);
        if (!actor.hasRole("ADMIN")) {
            requireOwner(auction, actor);
        }
        if ((auction.getStatus() != AuctionStatus.CREATED && auction.getStatus() != AuctionStatus.SCHEDULED)
                || !clock.instant().isBefore(auction.getStartAt())) {
            throw new DomainException(AuctionErrorCode.AUCTION_NOT_EDITABLE);
        }
        if (auctions.hasValidBids(id)) {
            throw new DomainException(AuctionErrorCode.AUCTION_HAS_BIDS);
        }
        validateWindow(request.startAt(), request.endAt());
        auction.configure(request.startingPrice(), request.bidIncrement(), request.startAt(), request.endAt());
        return AuctionResponse.from(auction);
    }

    @Transactional
    public AuctionResponse cancel(UUID id, CancelAuctionRequest request, CurrentUser actor, boolean admin) {
        if (admin && !actor.hasRole("ADMIN") || !admin && !actor.hasRole("SELLER")) {
            throw new DomainException(AuctionErrorCode.AUCTION_FORBIDDEN);
        }
        Auction auction = lock(id);
        if (!admin) {
            requireOwner(auction, actor);
        }
        if (!stateMachine.canTransition(auction.getStatus(), AuctionStatus.CANCELLED)) {
            throw new DomainException(AuctionErrorCode.AUCTION_ILLEGAL_TRANSITION);
        }
        if (!admin && auctions.hasValidBids(id)) {
            throw new DomainException(AuctionErrorCode.AUCTION_HAS_BIDS);
        }
        stateMachine.cancel(auction, actor);
        publishCancellation(auction, admin ? "ADMIN" : "SELLER", actor.id(), request.reason().strip());
        return AuctionResponse.from(auction);
    }

    @Transactional
    public void cancelForUnavailableStock(UUID auctionId, UUID skuId) {
        var candidate = auctions.findForUpdateByIdAndDeletedAtIsNull(auctionId);
        if (candidate.isEmpty() || !candidate.get().getSkuId().equals(skuId)) {
            return;
        }
        Auction auction = candidate.get();
        if (stateMachine.cancelForUnavailableStock(auction)) {
            publishCancellation(auction, "SYSTEM", SYSTEM_ACTOR, "STOCK_UNAVAILABLE");
        }
    }

    private void publishCancellation(Auction auction, String cancelledBy, UUID actorId, String reason) {
        auction.recordCancellation(cancelledBy, actorId, reason,
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        // Flush once before the event so its version is the committed entity version.
        auctions.flush();
        outbox.write("Auction", Topics.AUCTION,
                // Snapshot bidder trong transaction đang giữ khóa auction, cho cả hủy thủ công và hủy hệ thống.
                envelopes.create("AuctionCancelled", auction.getId().toString(),
                        AuctionCancelled.from(auction, bids.findDistinctBidderIdsByAuctionId(auction.getId()))));
    }

    private Auction lock(UUID id) {
        return auctions.findForUpdateByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new DomainException(AuctionErrorCode.AUCTION_NOT_FOUND));
    }

    private void requireOwner(Auction auction, CurrentUser actor) {
        if (!auction.getSellerId().equals(actor.id())) {
            throw new DomainException(AuctionErrorCode.AUCTION_FORBIDDEN);
        }
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
