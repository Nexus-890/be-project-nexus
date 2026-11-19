package com.group5.commerceservice.order;

import com.group5.common_core.model.ApiResponse;
import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.dto.CatalogSkuResponse;
import com.group5.commerceservice.order.dto.request.CreateOrderRequest.ShippingAddress;
import com.group5.commerceservice.order.service.InventoryClient;
import com.group5.commerceservice.order.service.OrderShippingService;
import com.group5.commerceservice.order.service.PricingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SellerPickupPricingTest {
    private static final ShippingAddress ADDRESS = new ShippingAddress("Buyer", "0901234567", "1 Main Street",
            "1003646", "1000001", "VN");
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final OrderShippingService shipping = mock(OrderShippingService.class);
    private final InventoryClient inventory = mock(InventoryClient.class);
    private final PricingService pricing = new PricingService(catalog, shipping, inventory);
    private final UUID seller = UUID.randomUUID();
    private final UUID warehouse = UUID.randomUUID();
    private final OrderShippingService.Pickup pickup = new OrderShippingService.Pickup(warehouse, "201", "20309", "12 Kim Mã");
    private final List<OrderShippingService.Line> lines = List.of(new OrderShippingService.Line(UUID.randomUUID(), 1, null));

    @Test
    void previewUsesTheSellerPickupWhenInventoryHasOneAndThePlatformPickupOtherwise() {
        var withPickup = new OrderShippingService.Offer(null, "STANDARD", List.of(), pickup);
        var platform = new OrderShippingService.Offer(null, "STANDARD", List.of());
        when(shipping.offer(ADDRESS, lines, pickup)).thenReturn(withPickup);
        when(shipping.offer(ADDRESS, lines)).thenReturn(platform);

        when(inventory.pickup(seller)).thenReturn(Optional.of(pickup));
        assertThat(pricing.shippingOffer(ADDRESS, lines, seller)).isSameAs(withPickup);
        when(inventory.pickup(seller)).thenReturn(Optional.empty());
        assertThat(pricing.shippingOffer(ADDRESS, lines, seller)).isSameAs(platform);
        assertThat(pricing.shippingOffer(ADDRESS, lines, null)).isSameAs(platform);
    }

    @Test
    void auctionUsesTheSellerPickupOnlyWhenTheHoldIsInTheSellerWarehouse() {
        UUID sku = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        var item = new CatalogSkuResponse();
        item.setId(sku);
        item.setProductId(product);
        when(catalog.getSkuById(sku)).thenReturn(ApiResponse.of(item));
        when(inventory.pickup(seller)).thenReturn(Optional.of(pickup));
        var eta = OffsetDateTime.now().plusDays(1);
        when(shipping.quoteAuction(eq(ADDRESS), anyList(), eq(warehouse), eq(pickup))).thenReturn(new OrderShippingService.Quote(
                new BigDecimal("31000.00"), null, "GHN", "STANDARD", eta, pickup));
        when(shipping.quoteAuction(eq(ADDRESS), anyList(), any(UUID.class))).thenReturn(new OrderShippingService.Quote(
                new BigDecimal("30000.00"), null, "GHN", "STANDARD", eta));

        var own = pricing.auctionShippingQuote(ADDRESS, sku, product, warehouse, new BigDecimal("100000"), seller);
        assertThat(own.pickup()).isEqualTo(pickup);
        assertThat(own.fee()).isEqualByComparingTo("31000.00");

        UUID legacy = UUID.randomUUID();
        var other = pricing.auctionShippingQuote(ADDRESS, sku, product, legacy, new BigDecimal("100000"), seller);
        assertThat(other.pickup()).isNull();
        verify(shipping).quoteAuction(eq(ADDRESS), anyList(), eq(legacy));
        verify(shipping, never()).quoteAuction(eq(ADDRESS), anyList(), eq(legacy), any());
    }
}
