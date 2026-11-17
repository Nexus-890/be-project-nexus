package com.group5.commerceservice.promotion;

import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import feign.FeignException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.group5.commerceservice.promotion.MegaSaleEventService.now;
import static com.group5.commerceservice.promotion.Voucher.*;
import static com.group5.commerceservice.promotion.VoucherPricing.Discount;
import static com.group5.commerceservice.promotion.VoucherPricing.Source;
import static com.group5.commerceservice.shared.exception.CommerceErrorCode.*;

@Service
@Transactional(readOnly = true)
class VoucherService {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private final VoucherRepository vouchers;
    private final VoucherLimitRepository limits;
    private final MegaSaleEventRepository events;
    private final CatalogClient catalog;

    VoucherService(VoucherRepository vouchers, VoucherLimitRepository limits, MegaSaleEventRepository events,
                   CatalogClient catalog) {
        this.vouchers = vouchers;
        this.limits = limits;
        this.events = events;
        this.catalog = catalog;
    }

    @Transactional
    View create(CurrentUser user, Write request) {
        boolean shop = request.sourceType() == Source.SHOP;
        if (!user.hasRole(shop ? "SELLER" : "ADMIN")) throw new DomainException(FORBIDDEN);
        UUID owner = shop ? user.id() : null;
        BigDecimal percent = validate(request);
        UUID id = vouchers.create(request, owner, user.id(), percent)
                .orElseThrow(() -> new DomainException(VOUCHER_CODE_ALREADY_EXISTS));
        return view(find(id));
    }

    Page<View> manage(CurrentUser user, Source source, Status status, UUID event, int page, int size) {
        return vouchers.manage(user.hasRole("ADMIN") ? null : user.id(), source, status, event, now(),
                page(page, size)).map(this::view);
    }

    View detail(CurrentUser user, UUID id) {
        Voucher voucher = find(id);
        requireOwner(user, voucher);
        return view(voucher);
    }

    @Transactional
    View edit(CurrentUser user, UUID id, Write request) {
        Voucher voucher = lock(id);
        requireOwner(user, voucher);
        requireDraft(voucher);
        if (request.sourceType() != voucher.sourceType() || !Objects.equals(request.eventId(), voucher.eventId())) {
            throw new DomainException(VOUCHER_INVALID_REQUEST, "Voucher source and event cannot change");
        }
        BigDecimal percent = validate(request);
        try {
            vouchers.edit(voucher, request, user.id(), percent, now());
        } catch (DuplicateKeyException conflict) {
            throw new DomainException(VOUCHER_CODE_ALREADY_EXISTS);
        }
        if (request.productScope() == Scope.ALL && voucher.productScope() == Scope.SELECTED) {
            vouchers.replaceProducts(id, Set.of(), user.id());
        }
        return view(find(id));
    }

    @Transactional
    View status(CurrentUser user, UUID id, Action action) {
        Voucher voucher = lock(id);
        requireOwner(user, voucher);
        OffsetDateTime now = now();
        if (voucher.status() == Status.CANCELED || voucher.status() == Status.EXPIRED || !now.isBefore(voucher.validUntil())) {
            throw new DomainException(VOUCHER_ILLEGAL_TRANSITION);
        }
        Status target = switch (action) {
            case ACTIVE -> {
                if (voucher.status() != Status.DRAFT && voucher.status() != Status.PAUSED) {
                    throw new DomainException(VOUCHER_ILLEGAL_TRANSITION);
                }
                if (voucher.productScope() == Scope.SELECTED && vouchers.products(id).isEmpty()) {
                    throw new DomainException(VOUCHER_INVALID_REQUEST, "Select at least one product before activating");
                }
                yield Status.ACTIVE;
            }
            case PAUSED -> {
                if (voucher.status() != Status.ACTIVE) throw new DomainException(VOUCHER_ILLEGAL_TRANSITION);
                yield Status.PAUSED;
            }
            case CANCELED -> Status.CANCELED;
        };
        if (target == Status.ACTIVE && voucher.sourceType() == Source.EVENT) requireJoinableEvent(voucher.eventId(), now);
        vouchers.status(id, target, user.id(), now);
        return view(find(id));
    }

    @Transactional
    View products(CurrentUser user, UUID id, Set<UUID> productIds) {
        Voucher voucher = lock(id);
        requireOwner(user, voucher);
        requireDraft(voucher);
        if (voucher.productScope() != Scope.SELECTED) throw new DomainException(VOUCHER_INVALID_REQUEST);
        for (UUID productId : productIds) {
            var product = product(productId);
            if (voucher.sourceType() == Source.SHOP && !voucher.ownerSellerId().equals(product.sellerId())) {
                throw new DomainException(FORBIDDEN);
            }
        }
        vouchers.replaceProducts(id, productIds, user.id());
        return view(voucher);
    }

    @Transactional
    Participation join(UUID seller, UUID id, int version) {
        Voucher voucher = lock(id);
        OffsetDateTime now = now();
        if (voucher.sourceType() != Source.EVENT || voucher.status() != Status.ACTIVE || !now.isBefore(voucher.validUntil())) {
            throw new DomainException(VOUCHER_ILLEGAL_TRANSITION);
        }
        requireJoinableEvent(voucher.eventId(), now);
        UUID registration = events.approvedSeller(voucher.eventId(), seller)
                .orElseThrow(() -> new DomainException(FORBIDDEN)).id();
        if (voucher.termsVersion() != version) throw new DomainException(VOUCHER_TERMS_CHANGED);
        return vouchers.join(voucher, registration, seller, now);
    }

    @Transactional
    void withdraw(UUID seller, UUID id) {
        Voucher voucher = lock(id);
        OffsetDateTime now = now();
        if (voucher.sourceType() != Source.EVENT || !now.isBefore(voucher.validFrom())) {
            throw new DomainException(VOUCHER_ILLEGAL_TRANSITION);
        }
        if (!vouchers.withdraw(id, seller, now)) throw new DomainException(VOUCHER_PARTICIPATION_NOT_FOUND);
    }

    Page<PublicView> publicList(UUID productId, UUID sellerId, int page, int size) {
        PageRequest paging = page(page, size);
        if (productId != null) {
            var product = product(productId);
            if (!"ACTIVE".equals(product.status()) || (sellerId != null && !sellerId.equals(product.sellerId()))) {
                return Page.empty(paging);
            }
            sellerId = product.sellerId();
        }
        return vouchers.claimable(productId, sellerId, now(), paging).map(PublicView::of);
    }

    @Transactional
    WalletEntry claim(UUID buyer, UUID id) {
        Voucher voucher = lock(id);
        OffsetDateTime now = now();
        if (vouchers.claim(buyer, id).isPresent()) throw new DomainException(VOUCHER_ALREADY_CLAIMED);
        if (!voucher.claimable(now)) throw new DomainException(VOUCHER_CLAIM_UNAVAILABLE);
        if (voucher.sourceType() == Source.EVENT) requireJoinableEvent(voucher.eventId(), now);
        if (!limits.takeClaim(id, now)) throw new DomainException(VOUCHER_CLAIM_LIMIT_REACHED);
        Claim claim = vouchers.insertClaim(buyer, voucher, now);
        return new WalletEntry(claim.id(), claim.status(), claim.claimedAt(), claim.expiresAt(), PublicView.of(voucher));
    }

    Page<WalletEntry> wallet(UUID buyer, WalletStatus status, int page, int size) {
        return vouchers.wallet(buyer, status, now(), page(page, size))
                .map(claim -> new WalletEntry(claim.id(), claim.status(), claim.claimedAt(), claim.expiresAt(),
                        PublicView.of(find(claim.voucherId()))));
    }

    Page<Usage> usages(CurrentUser user, UUID id, int page, int size) {
        requireOwner(user, find(id));
        return vouchers.usages(id, page(page, size));
    }

    private BigDecimal validate(Write r) {
        if (!r.claimEndAt().isAfter(r.claimStartAt()) || !r.validUntil().isAfter(r.validFrom())
                || r.claimEndAt().isAfter(r.validUntil())
                || (r.discountType() == Discount.PERCENT
                    && (r.discountValue().compareTo(BigDecimal.ONE) < 0 || r.discountValue().compareTo(HUNDRED) > 0))
                || (r.sourceType() == Source.PLATFORM && r.fundingType() != Funding.PLATFORM)
                || (r.sourceType() == Source.SHOP && r.fundingType() != Funding.SELLER)
                || (r.sourceType() == Source.EVENT) != (r.eventId() != null)) {
            throw new DomainException(VOUCHER_INVALID_REQUEST);
        }
        if (r.eventId() != null) {
            var event = requireJoinableEvent(r.eventId(), now());
            if (r.validFrom().isBefore(event.startAt()) || r.validUntil().isAfter(event.endAt())) {
                throw new DomainException(VOUCHER_INVALID_REQUEST, "An event voucher must be valid within the event");
            }
        }
        return switch (r.fundingType()) {
            case PLATFORM -> BigDecimal.ZERO;
            case SELLER -> HUNDRED;
            case CO_FUNDED -> {
                if (r.sellerFundingPercent() == null || r.sellerFundingPercent().signum() <= 0
                        || r.sellerFundingPercent().compareTo(HUNDRED) >= 0) {
                    throw new DomainException(VOUCHER_INVALID_REQUEST, "Co-funded vouchers require a seller funding percentage between 0 and 100");
                }
                yield r.sellerFundingPercent();
            }
        };
    }

    private MegaSaleEvent requireJoinableEvent(UUID id, OffsetDateTime now) {
        MegaSaleEvent event = events.share(id).orElseThrow(() -> new DomainException(PROMOTION_EVENT_NOT_FOUND));
        if (event.status() == MegaSaleEvent.Status.CANCELED || !now.isBefore(event.endAt())) {
            throw new DomainException(VOUCHER_ILLEGAL_TRANSITION);
        }
        return event;
    }

    private CatalogClient.ProductResponse product(UUID id) {
        try {
            var response = catalog.getProductById(id);
            var product = response == null ? null : response.data();
            if (product == null || !id.equals(product.id()) || product.sellerId() == null || product.status() == null) {
                throw new DomainException(INVALID_CATALOG_RESPONSE);
            }
            return product;
        } catch (FeignException.NotFound error) {
            throw new DomainException(VOUCHER_PRODUCT_NOT_FOUND);
        } catch (FeignException error) {
            throw new DomainException(CATALOG_UNAVAILABLE);
        }
    }

    private static void requireOwner(CurrentUser user, Voucher voucher) {
        if (!user.hasRole("ADMIN") && !(user.hasRole("SELLER") && user.id().equals(voucher.ownerSellerId()))) {
            throw new DomainException(FORBIDDEN);
        }
    }

    private static void requireDraft(Voucher voucher) {
        if (voucher.status() != Status.DRAFT) throw new DomainException(VOUCHER_ILLEGAL_TRANSITION);
    }

    private View view(Voucher voucher) {
        return new View(voucher, vouchers.products(voucher.id()));
    }

    private Voucher find(UUID id) {
        return vouchers.find(id).orElseThrow(() -> new DomainException(VOUCHER_NOT_FOUND));
    }

    private Voucher lock(UUID id) {
        return vouchers.lock(id).orElseThrow(() -> new DomainException(VOUCHER_NOT_FOUND));
    }

    private static PageRequest page(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new DomainException(VOUCHER_INVALID_REQUEST);
        return PageRequest.of(page, size);
    }
}
