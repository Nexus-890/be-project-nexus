package com.group5.commerceservice.promotion;

import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.entity.OrderItem;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.group5.commerceservice.promotion.VoucherPricing.Allocation;
import static com.group5.commerceservice.promotion.VoucherPricing.Offer;

@Repository
class VoucherUsageRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    VoucherUsageRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void insert(UUID claimId, Order order, Offer offer, List<Allocation> allocations, OffsetDateTime now) {
        BigDecimal eligible = allocations.stream().map(row -> row.line().amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discount = allocations.stream().map(Allocation::discountAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sellerFunded = allocations.stream().map(Allocation::sellerFunded).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal platformFunded = discount.subtract(sellerFunded);
        UUID usageId = jdbc.sql("""
                INSERT INTO promo_voucher_usages (customer_voucher_id, order_id, idempotency_key, status, eligible_amount,
                    discount_amount, seller_funded_amount, platform_funded_amount, voucher_snapshot,
                    reserved_at, reserved_until, updated_at, created_by, updated_by)
                VALUES (:claim, :order, :key, 'RESERVED', :eligible, :discount, :sellerFunded, :platformFunded,
                    CAST(:snapshot AS jsonb), :now, :due, :now, :actor, :actor)
                RETURNING id
                """).param("claim", claimId).param("order", order.getId()).param("key", order.getId() + ":" + offer.id())
                .param("eligible", eligible).param("discount", discount).param("sellerFunded", sellerFunded)
                .param("platformFunded", platformFunded).param("snapshot", json.writeValueAsString(offer))
                .param("now", now).param("due", order.getPaymentDueAt()).param("actor", order.getUserId())
                .query(UUID.class).single();

        Map<UUID, OrderItem> items = order.getItems().stream()
                .collect(java.util.stream.Collectors.toMap(OrderItem::getSkuId, item -> item));
        for (Allocation row : allocations) {
            OrderItem item = items.get(row.line().skuId());
            if (item == null) throw new IllegalStateException("Voucher allocation does not match a checkout order item");
            jdbc.sql("""
                    INSERT INTO promo_voucher_usage_allocations (
                        voucher_usage_id, order_item_id, seller_id, voucher_seller_id, eligible_amount,
                        discount_amount, seller_funded_amount, platform_funded_amount, created_by, updated_by)
                    VALUES (:usage, :item, :seller, :participant, :eligible, :discount, :sellerFunded,
                            :platformFunded, :actor, :actor)
                    """).param("usage", usageId).param("item", item.getId()).param("seller", order.getSellerId())
                    .param("participant", row.voucherSellerId()).param("eligible", row.line().amount())
                    .param("discount", row.discountAmount()).param("sellerFunded", row.sellerFunded())
                    .param("platformFunded", row.platformFunded()).param("actor", order.getUserId()).update();
        }
    }

    void lockVouchers(List<UUID> orders, Status from) {
        jdbc.sql("""
                SELECT v.id FROM promo_vouchers v
                WHERE EXISTS (
                    SELECT 1 FROM promo_voucher_usages u
                    JOIN promo_customer_vouchers c ON c.id = u.customer_voucher_id
                    WHERE u.order_id IN (:orders) AND u.status = CAST(:from AS promo_voucher_usage_status)
                        AND c.voucher_id = v.id)
                ORDER BY v.id FOR UPDATE
                """).param("orders", orders).param("from", from.name()).query(UUID.class).list();
    }

    List<Change> move(List<UUID> orders, Status from, Status to, OffsetDateTime now, UUID actor, String reason) {
        return jdbc.sql("""
                UPDATE promo_voucher_usages u SET status = CAST(:to AS promo_voucher_usage_status),
                    redeemed_at = CASE WHEN :to = 'REDEEMED' THEN :now ELSE redeemed_at END,
                    released_at = CASE WHEN :to IN ('RELEASED', 'EXPIRED') THEN :now ELSE released_at END,
                    release_reason = CASE WHEN :to IN ('RELEASED', 'EXPIRED') THEN :reason ELSE release_reason END,
                    updated_at = :now, updated_by = :actor
                FROM promo_customer_vouchers c
                WHERE u.customer_voucher_id = c.id AND u.order_id IN (:orders)
                    AND u.status = CAST(:from AS promo_voucher_usage_status)
                RETURNING u.id, u.order_id, c.voucher_id, c.id AS claim_id,
                          u.discount_amount, u.seller_funded_amount, u.platform_funded_amount
                """).param("orders", orders).param("from", from.name()).param("to", to.name()).param("now", now)
                .param("actor", actor).param("reason", reason).query(Change.class).list();
    }

    void walletUsed(UUID claim, OffsetDateTime now, UUID actor) {
        jdbc.sql("""
                UPDATE promo_customer_vouchers SET status = 'USED', updated_at = :now, updated_by = :actor
                WHERE id = :claim AND status <> 'USED'
                """).param("claim", claim).param("now", now).param("actor", actor).update();
    }

    void walletReleased(UUID claim, OffsetDateTime now, UUID actor) {
        jdbc.sql("""
                UPDATE promo_customer_vouchers
                SET status = CAST(CASE WHEN expires_at <= :now THEN 'EXPIRED' ELSE 'CLAIMED' END AS promo_customer_voucher_status),
                    updated_at = :now, updated_by = :actor
                WHERE id = :claim AND status <> 'USED'
                """).param("claim", claim).param("now", now).param("actor", actor).update();
    }

    List<RefundAllocation> refundable(UUID usage) {
        return jdbc.sql("""
                SELECT id, order_item_id, discount_amount, seller_funded_amount, platform_funded_amount
                FROM promo_voucher_usage_allocations
                WHERE voucher_usage_id = :usage ORDER BY eligible_amount DESC, order_item_id
                """).param("usage", usage).query(RefundAllocation.class).list();
    }

    void reverseAllocation(UUID id, BigDecimal amount, BigDecimal seller, OffsetDateTime now, UUID actor) {
        jdbc.sql("""
                UPDATE promo_voucher_usage_allocations SET reversed_discount_amount = :amount,
                    reversed_seller_amount = :seller, reversed_platform_amount = :amount - :seller,
                    updated_at = :now, updated_by = :actor WHERE id = :id
                """).param("id", id).param("amount", amount).param("seller", seller)
                .param("now", now).param("actor", actor).update();
    }

    enum Status { RESERVED, REDEEMED, RELEASED, EXPIRED, REVERSED }

    record Change(UUID id, UUID orderId, UUID voucherId, UUID claimId, BigDecimal discountAmount,
                  BigDecimal sellerFundedAmount, BigDecimal platformFundedAmount) { }

    record RefundAllocation(UUID id, UUID orderItemId, BigDecimal discountAmount,
                            BigDecimal sellerFundedAmount, BigDecimal platformFundedAmount) { }

    List<VoucherCheckoutService.OrderVoucher> summary(UUID orderId) {
        return jdbc.sql("""
                SELECT voucher_snapshot ->> 'code' AS code, discount_amount,
                       seller_funded_amount AS seller_funded, platform_funded_amount AS platform_funded
                FROM promo_voucher_usages WHERE order_id = :order ORDER BY code, id
                """).param("order", orderId).query(VoucherCheckoutService.OrderVoucher.class).list();
    }

    Map<UUID, List<VoucherCheckoutService.OrderVoucher>> summaries(List<UUID> orderIds) {
        if (orderIds.isEmpty()) return Map.of();
        Map<UUID, List<VoucherCheckoutService.OrderVoucher>> results = new HashMap<>();
        jdbc.sql("""
                SELECT order_id, voucher_snapshot ->> 'code' AS code, discount_amount,
                       seller_funded_amount, platform_funded_amount
                FROM promo_voucher_usages WHERE order_id IN (:orders) ORDER BY order_id, code, id
                """).param("orders", orderIds).query().listOfRows().forEach(row ->
                results.computeIfAbsent((UUID) row.get("order_id"), ignored -> new ArrayList<>())
                        .add(new VoucherCheckoutService.OrderVoucher((String) row.get("code"),
                                (BigDecimal) row.get("discount_amount"), (BigDecimal) row.get("seller_funded_amount"),
                                (BigDecimal) row.get("platform_funded_amount"))));
        return results;
    }
}
