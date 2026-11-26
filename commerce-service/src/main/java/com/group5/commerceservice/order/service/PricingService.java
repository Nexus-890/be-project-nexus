package com.group5.commerceservice.order.service;

import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.dto.CatalogSkuResponse;
import com.group5.commerceservice.cart.entity.CartItem;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import com.group5.commerceservice.order.dto.request.CreateOrderRequest.ShippingAddress;
import com.group5.common_core.exception.DomainException;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Collection;
import com.group5.commerceservice.order.entity.Order;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PricingService {
    private final CatalogClient catalogClient;
    private final OrderShippingService shipping;
    private final InventoryClient inventory;

    public void snapshotCosts(Collection<Order> orders) {
        List<UUID> ids = orders.stream().flatMap(order -> order.getItems().stream())
                .map(item -> item.getSkuId()).distinct().sorted().toList();
        if (ids.isEmpty()) return;
        var costs = readCosts(ids);
        for (var order : orders) {
            for (var item : order.getItems()) {
                var cost = costs.get(item.getSkuId());
                if (!order.getSellerId().equals(cost.sellerId()) || !item.getProductId().equals(cost.productId())) throw invalidCost();
                item.setUnitCostPrice(cost.unitCostPrice() == null ? null : cost.unitCostPrice().setScale(2));
            }
        }
    }

    private Map<UUID, CatalogClient.Cost> readCosts(List<UUID> ids) {
        try {
            var response = catalogClient.getCosts(new CatalogClient.CostRequest(ids));
            if (response == null || response.code() != 0 || response.data() == null) throw invalidCost();
            var indexed = new HashMap<UUID, CatalogClient.Cost>();
            for (var cost : response.data()) {
                if (cost == null || cost.skuId() == null || cost.productId() == null || cost.sellerId() == null
                        || !ids.contains(cost.skuId()) || indexed.putIfAbsent(cost.skuId(), cost) != null) throw invalidCost();
                if (cost.unitCostPrice() != null && (cost.unitCostPrice().signum() < 0
                        || cost.unitCostPrice().stripTrailingZeros().scale() > 2
                        || cost.unitCostPrice().setScale(2).precision() > 19)) throw invalidCost();
            }
            if (indexed.size() != ids.size()) throw invalidCost();
            return indexed;
        } catch (FeignException failure) {
            throw new DomainException(CommerceErrorCode.CATALOG_UNAVAILABLE);
        }
    }

    private static DomainException invalidCost() { return new DomainException(CommerceErrorCode.INVALID_CATALOG_RESPONSE); }

    @Value("${nexus.checkout.free-shipping-threshold:1000000}")
    private BigDecimal freeShippingThreshold = new BigDecimal("1000000");

    public OrderShippingService.Quote auctionShippingQuote(ShippingAddress address, UUID skuId,
                                                           UUID productId, UUID warehouseId, BigDecimal finalPrice) {
        return auctionShippingQuote(address, skuId, productId, warehouseId, finalPrice, null);
    }

    public OrderShippingService.Quote auctionShippingQuote(ShippingAddress address, UUID skuId, UUID productId,
                                                           UUID warehouseId, BigDecimal finalPrice, UUID sellerId) {
        CatalogSkuResponse sku;
        try {
            var response = catalogClient.getSkuById(skuId);
            sku = response == null ? null : response.data();
        } catch (FeignException error) {
            throw new DomainException(CommerceErrorCode.CATALOG_UNAVAILABLE);
        }
        if (sku == null || !skuId.equals(sku.getId()) || !productId.equals(sku.getProductId())) {
            throw new DomainException(CommerceErrorCode.INVALID_CATALOG_RESPONSE);
        }
        var lines = List.of(new OrderShippingService.Line(skuId, 1, sku));
        var pickup = pickup(sellerId, lines);
        var quote = pickup != null && pickup.warehouseId().equals(warehouseId)
                ? shipping.quoteAuction(address, lines, warehouseId, pickup)
                : shipping.quoteAuction(address, lines, warehouseId);
        BigDecimal fee = finalPrice.compareTo(freeShippingThreshold) >= 0 ? new BigDecimal("0.00") : quote.fee();
        return new OrderShippingService.Quote(fee, quote.parcel(), quote.carrier(), quote.serviceType(),
                quote.estimatedDeliveryAt(), quote.pickup());
    }

    /** Preview và tạo đơn dùng chung một nguồn phí, không nhận giá vận chuyển do client gửi lên. */
    public OrderShippingService.Quote shippingQuote(ShippingAddress address, List<OrderShippingService.Line> lines) {
        return shipping.quote(address, lines);
    }

    public OrderShippingService.Quote shippingQuote(ShippingAddress address, List<OrderShippingService.Line> lines,
                                                    String carrier) {
        return carrier == null ? shipping.quote(address, lines) : shipping.offer(address, lines).select(carrier);
    }

    public OrderShippingService.Offer shippingOffer(ShippingAddress address, List<OrderShippingService.Line> lines) {
        return shipping.offer(address, lines);
    }

    /** Kho riêng của seller có đủ tỉnh/phường thì hãng vận chuyển lấy hàng tại đó, không dùng địa chỉ nền tảng. */
    public OrderShippingService.Offer shippingOffer(ShippingAddress address, List<OrderShippingService.Line> lines,
                                                    UUID sellerId) {
        var pickup = pickup(sellerId, lines);
        return pickup == null ? shipping.offer(address, lines) : shipping.offer(address, lines, pickup);
    }

    public OrderShippingService.Quote shippingQuote(ShippingAddress address, List<OrderShippingService.Line> lines,
                                                    String carrier, UUID sellerId) {
        var pickup = pickup(sellerId, lines);
        return pickup == null ? shippingQuote(address, lines, carrier) : shipping.offer(address, lines, pickup).select(carrier);
    }

    private OrderShippingService.Pickup pickup(UUID sellerId, List<OrderShippingService.Line> lines) {
        return sellerId == null ? null
                : inventory.pickup(sellerId, lines.stream().map(OrderShippingService.Line::skuId).toList()).orElse(null);
    }

    @Value("${nexus.checkout.price-revalidation:true}")
    private boolean priceRevalidation = true;

    public Map<UUID, CatalogSkuResponse> revalidate(List<CartItem> lines) {
        Map<UUID, CatalogSkuResponse> skus = new HashMap<>();
        for (CartItem line : lines.stream().sorted(Comparator.comparing(CartItem::getId)).toList()) {
            CatalogSkuResponse sku = readSku(line);
            skus.put(line.getSkuId(), sku);
            if (priceRevalidation && minorUnits(line.getUnitPrice()) != minorUnits(sku.getPrice())) {
                BigDecimal oldPrice = line.getUnitPrice();
                // Only refresh the price reported to the buyer; other changes need their own confirmation.
                line.setUnitPrice(sku.getPrice());
                throw new DomainException(CommerceErrorCode.PRICE_CHANGED, CommerceErrorCode.PRICE_CHANGED.message(),
                        Map.of("skuId", line.getSkuId(), "oldPrice", oldPrice, "newPrice", sku.getPrice()));
            }
        }
        return skus;
    }

    private long minorUnits(BigDecimal price) {
        try {
            if (price == null || price.signum() < 0) {
                throw new ArithmeticException("Invalid price");
            }
            // Catalog and cart prices have two decimal places. Never round or truncate them.
            return price.movePointRight(2).longValueExact();
        } catch (ArithmeticException exception) {
            throw new DomainException(CommerceErrorCode.INVALID_ORDER_AMOUNT);
        }
    }

    private CatalogSkuResponse readSku(CartItem line) {
        CatalogSkuResponse sku;
        try {
            var response = catalogClient.getSkuById(line.getSkuId());
            sku = response == null ? null : response.data();
        } catch (FeignException.NotFound exception) {
            throw new DomainException(CommerceErrorCode.ITEM_UNAVAILABLE, "A cart item is no longer available");
        } catch (FeignException exception) {
            throw new DomainException(CommerceErrorCode.CATALOG_UNAVAILABLE, "Catalog is unavailable; retry placing the order later");
        }
        if (sku == null || !line.getSkuId().equals(sku.getId()) || !line.getProductId().equals(sku.getProductId())
                || !"ACTIVE".equals(sku.getStatus()) || sku.getPrice() == null || sku.getPrice().signum() < 0) {
            throw new DomainException(CommerceErrorCode.ITEM_UNAVAILABLE, "A cart item is no longer available");
        }
        return sku;
    }
}
