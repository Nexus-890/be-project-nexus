package com.group5.commerceservice.promotion;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static com.group5.commerceservice.promotion.VoucherPricing.Discount;
import static com.group5.commerceservice.promotion.VoucherPricing.Source;

public record Voucher(UUID id, String code, String name, String description, Source sourceType,
                      UUID ownerSellerId, UUID eventId, Discount discountType, BigDecimal discountValue,
                      BigDecimal maxDiscountAmount, BigDecimal minOrderAmount, Funding fundingType,
                      BigDecimal sellerFundingPercent, Scope productScope, Integer claimLimit,
                      Integer usageLimit, BigDecimal discountBudget, OffsetDateTime claimStartAt,
                      OffsetDateTime claimEndAt, OffsetDateTime validFrom, OffsetDateTime validUntil,
                      String termsText, int termsVersion, Status status, long claimCount,
                      long committedUses, BigDecimal committedDiscount, OffsetDateTime createdAt,
                      OffsetDateTime updatedAt) {
    public enum Status { DRAFT, ACTIVE, PAUSED, EXPIRED, CANCELED }
    public enum Action { ACTIVE, PAUSED, CANCELED }
    public enum Funding { PLATFORM, SELLER, CO_FUNDED }
    public enum Scope { ALL, SELECTED }
    public enum WalletStatus { CLAIMED, USED, EXPIRED }
    public enum SellerStatus { ACTIVE, WITHDRAWN }

    public record Write(@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,49}") String code,
                        @NotBlank @Size(max = 200) String name, String description, @NotNull Source sourceType,
                        UUID eventId, @NotNull Discount discountType, @NotNull @Positive @Digits(integer = 17, fraction = 2)
                        BigDecimal discountValue, @PositiveOrZero @Digits(integer = 17, fraction = 2)
                        BigDecimal maxDiscountAmount, @PositiveOrZero @Digits(integer = 17, fraction = 2)
                        BigDecimal minOrderAmount, @NotNull Funding fundingType,
                        @PositiveOrZero @DecimalMax("100") @Digits(integer = 3, fraction = 2)
                        BigDecimal sellerFundingPercent, @NotNull Scope productScope,
                        @Positive Integer claimLimit, @Positive Integer usageLimit,
                        @PositiveOrZero @Digits(integer = 17, fraction = 2) BigDecimal discountBudget,
                        @NotNull OffsetDateTime claimStartAt, @NotNull OffsetDateTime claimEndAt,
                        @NotNull OffsetDateTime validFrom, @NotNull OffsetDateTime validUntil,
                        String termsText) {
    }

    public record ChangeStatus(@NotNull Action status) { }
    public record Products(@NotNull @Size(max = 500) Set<@NotNull UUID> productIds) { }
    public record Join(@NotNull @Positive Integer acceptedTermsVersion) { }
    public record View(Voucher voucher, Set<UUID> productIds) { }

    public record PublicView(UUID id, String code, String name, String description, Source sourceType,
                             UUID ownerSellerId, UUID eventId, Discount discountType, BigDecimal discountValue,
                             BigDecimal maxDiscountAmount, BigDecimal minOrderAmount, Scope productScope,
                             OffsetDateTime claimStartAt, OffsetDateTime claimEndAt, OffsetDateTime validFrom,
                             OffsetDateTime validUntil, String termsText) {
        static PublicView of(Voucher v) {
            return new PublicView(v.id, v.code, v.name, v.description, v.sourceType, v.ownerSellerId, v.eventId,
                    v.discountType, v.discountValue, v.maxDiscountAmount, v.minOrderAmount, v.productScope,
                    v.claimStartAt, v.claimEndAt, v.validFrom, v.validUntil, v.termsText);
        }
    }

    public record Participation(UUID id, UUID voucherId, UUID eventSellerId, SellerStatus status,
                                int acceptedTermsVersion, String acceptedTermsSnapshot,
                                BigDecimal sellerFundingPercentSnapshot, OffsetDateTime acceptedAt,
                                OffsetDateTime withdrawnAt) { }

    public record Claim(UUID id, UUID customerId, UUID voucherId, WalletStatus status,
                        OffsetDateTime claimedAt, OffsetDateTime expiresAt) { }

    public record WalletEntry(UUID id, WalletStatus status, OffsetDateTime claimedAt, OffsetDateTime expiresAt,
                              PublicView voucher) { }

    public record Usage(UUID id, UUID orderId, String status, BigDecimal eligibleAmount,
                        BigDecimal discountAmount, BigDecimal sellerFunded, BigDecimal platformFunded,
                        OffsetDateTime reservedAt, OffsetDateTime reservedUntil,
                        OffsetDateTime redeemedAt, OffsetDateTime releasedAt) { }

    boolean claimable(OffsetDateTime now) {
        return status == Status.ACTIVE && !now.isBefore(claimStartAt) && now.isBefore(claimEndAt)
                && now.isBefore(validUntil);
    }
}
