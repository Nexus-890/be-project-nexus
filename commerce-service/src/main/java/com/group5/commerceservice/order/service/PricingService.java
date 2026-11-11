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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PricingService {
    private final CatalogClient catalogClient;
    private final OrderShippingService shipping;

    /** Preview và tạo đơn dùng chung một nguồn phí, không nhận giá vận chuyển do client gửi lên. */
    public OrderShippingService.Quote shippingQuote(ShippingAddress address, List<OrderShippingService.Line> lines) {
        return shipping.quote(address, lines);
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
