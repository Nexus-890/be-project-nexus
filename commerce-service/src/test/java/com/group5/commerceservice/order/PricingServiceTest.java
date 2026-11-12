package com.group5.commerceservice.order;

import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.dto.CatalogSkuResponse;
import com.group5.commerceservice.cart.entity.CartItem;
import com.group5.commerceservice.order.service.PricingService;
import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PricingServiceTest {
    private final CatalogClient catalogClient = mock(CatalogClient.class);
    private final PricingService pricingService = new PricingService(catalogClient,
            mock(com.group5.commerceservice.order.service.OrderShippingService.class));

    @Test
    void changedPriceReportsBothFiguresAndRefreshesTheCartForConfirmation() {
        CartItem item = item(1, "150000.00");
        CatalogSkuResponse sku = currentSku(item, "180000.00");

        DomainException failure = assertThrows(DomainException.class,
                () -> pricingService.revalidate(List.of(item)));

        assertThat(failure.getCode()).isEqualTo(409);
        assertThat(failure.getErrorCode()).isEqualTo("COMMERCE_PRICE_CHANGED");
        assertThat(failure.getProperties()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "skuId", item.getSkuId(),
                "oldPrice", new BigDecimal("150000.00"),
                "newPrice", new BigDecimal("180000.00")));
        assertThat(item.getUnitPrice()).isEqualByComparingTo("180000.00");

        assertThat(pricingService.revalidate(List.of(item))).containsEntry(item.getSkuId(), sku);
        verify(catalogClient, times(2)).getSkuById(item.getSkuId());
    }

    @Test
    void unchangedPricesWithDifferentScalesAreAccepted() {
        CartItem item = item(1, "150000.00");
        CatalogSkuResponse sku = currentSku(item, "150000");

        assertThat(pricingService.revalidate(List.of(item))).containsExactlyEntriesOf(
                Map.of(item.getSkuId(), sku));
        assertThat(item.getUnitPrice()).isEqualByComparingTo("150000.00");
        verify(catalogClient).getSkuById(item.getSkuId());
    }

    @Test
    void disabledRevalidationPreservesThePriceTheBuyerSaw() {
        ReflectionTestUtils.setField(pricingService, "priceRevalidation", false);
        CartItem item = item(1, "150000.00");
        CatalogSkuResponse sku = currentSku(item, "180000.00");

        assertThat(pricingService.revalidate(List.of(item))).containsEntry(item.getSkuId(), sku);

        assertThat(item.getUnitPrice()).isEqualByComparingTo("150000.00");
        verify(catalogClient).getSkuById(item.getSkuId());
    }

    @Test
    void disabledRevalidationStillRejectsAnUnavailableSku() {
        ReflectionTestUtils.setField(pricingService, "priceRevalidation", false);
        CartItem item = item(1, "150000.00");
        currentSku(item, "180000.00").setStatus("INACTIVE");

        DomainException failure = assertThrows(DomainException.class,
                () -> pricingService.revalidate(List.of(item)));

        assertThat(failure.getErrorCode()).isEqualTo("COMMERCE_ITEM_UNAVAILABLE");
        assertThat(item.getUnitPrice()).isEqualByComparingTo("150000.00");
    }

    @Test
    void aOneMinorUnitChangeIsReportedWithoutTruncatingTheFraction() {
        CartItem item = item(1, "150000.50");
        currentSku(item, "150000.51");

        DomainException failure = assertThrows(DomainException.class,
                () -> pricingService.revalidate(List.of(item)));

        assertThat((BigDecimal) failure.getProperties().get("oldPrice"))
                .isEqualByComparingTo("150000.50");
        assertThat((BigDecimal) failure.getProperties().get("newPrice"))
                .isEqualByComparingTo("150000.51");
        assertThat(item.getUnitPrice()).isEqualByComparingTo("150000.51");
    }

    @Test
    void onlyTheReportedChangeIsAcceptedIntoTheCart() {
        CartItem first = item(1, "150000.00");
        CartItem second = item(2, "200000.00");
        currentSku(first, "180000.00");
        currentSku(second, "250000.00");

        DomainException failure = assertThrows(DomainException.class,
                () -> pricingService.revalidate(List.of(second, first)));

        assertThat(failure.getProperties()).containsEntry("skuId", first.getSkuId());
        assertThat(first.getUnitPrice()).isEqualByComparingTo("180000.00");
        assertThat(second.getUnitPrice()).isEqualByComparingTo("200000.00");
    }

    @Test
    void aChangeOnALaterCartLineIsAlsoRejectedBeforeCheckout() {
        CartItem first = item(1, "150000.00");
        CartItem second = item(2, "200000.00");
        currentSku(first, "150000.00");
        currentSku(second, "250000.00");

        DomainException failure = assertThrows(DomainException.class,
                () -> pricingService.revalidate(List.of(first, second)));

        assertThat(failure.getProperties()).containsEntry("skuId", second.getSkuId());
        assertThat(first.getUnitPrice()).isEqualByComparingTo("150000.00");
        assertThat(second.getUnitPrice()).isEqualByComparingTo("250000.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"150000.001", "92233720368547758.08"})
    void unrepresentablePricesAreRejectedWithoutRoundingOrRefreshingTheCart(String price) {
        CartItem item = item(1, "150000.00");
        currentSku(item, price);

        DomainException failure = assertThrows(DomainException.class,
                () -> pricingService.revalidate(List.of(item)));

        assertThat(failure.getErrorCode()).isEqualTo("COMMERCE_INVALID_ORDER_AMOUNT");
        assertThat(item.getUnitPrice()).isEqualByComparingTo("150000.00");
    }

    private CartItem item(long id, String price) {
        CartItem item = new CartItem();
        item.setId(new UUID(0, id));
        item.setProductId(UUID.randomUUID());
        item.setSkuId(UUID.randomUUID());
        item.setQuantity(1);
        item.setUnitPrice(new BigDecimal(price));
        return item;
    }

    private CatalogSkuResponse currentSku(CartItem item, String price) {
        CatalogSkuResponse sku = new CatalogSkuResponse();
        sku.setId(item.getSkuId());
        sku.setProductId(item.getProductId());
        sku.setSkuCode("SKU-" + item.getSkuId());
        sku.setPrice(new BigDecimal(price));
        sku.setStatus("ACTIVE");
        when(catalogClient.getSkuById(item.getSkuId())).thenReturn(ApiResponse.of(sku));
        return sku;
    }
}
