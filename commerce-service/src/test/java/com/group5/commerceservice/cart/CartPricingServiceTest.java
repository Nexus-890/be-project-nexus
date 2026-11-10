package com.group5.commerceservice.cart;

import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.dto.CatalogSkuResponse;
import com.group5.commerceservice.cart.entity.Cart;
import com.group5.commerceservice.cart.entity.CartItem;
import com.group5.commerceservice.cart.repository.CartItemRepository;
import com.group5.commerceservice.cart.repository.CartRepository;
import com.group5.commerceservice.cart.service.CartPricingService;
import com.group5.commerceservice.shared.config.CheckoutProperties;
import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import feign.FeignException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.group5.commerceservice.cart.dto.response.CartIssue.Code.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CartPricingServiceTest {
    private final CartRepository carts = mock(CartRepository.class);
    private final CartItemRepository items = mock(CartItemRepository.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final CheckoutProperties properties = new CheckoutProperties();
    private final CartPricingService service = new CartPricingService(carts, items, catalog, properties);
    private final CurrentUser user = new CurrentUser(UUID.randomUUID(), Set.of("BUYER"), Set.of());
    private final Cart cart = new Cart();
    private final CartItem line = new CartItem();
    private final CatalogSkuResponse sku = new CatalogSkuResponse();

    @BeforeEach void setup() {
        cart.setId(UUID.randomUUID());
        cart.setUserId(user.id());
        cart.setStatus(CartStatus.ACTIVE);
        line.setId(UUID.randomUUID());
        line.setCart(cart);
        line.setProductId(UUID.randomUUID());
        line.setSkuId(UUID.randomUUID());
        line.setQuantity(2);
        line.setUnitPrice(new BigDecimal("150000.00"));
        sku.setId(line.getSkuId());
        sku.setProductId(line.getProductId());
        sku.setPrice(new BigDecimal("150000"));
        sku.setStatus("ACTIVE");
        when(carts.findByUserIdAndStatus(user.id(), CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(items.findByCart_Id(cart.getId())).thenReturn(List.of(line));
        when(catalog.getSkuById(line.getSkuId())).thenReturn(ApiResponse.of(sku));
        product("ACTIVE");
    }

    @Test void allThreeOperationsUseCurrentPricesAndNeverWriteOrChangeTheSnapshot() {
        sku.setPrice(new BigDecimal("160000"));
        var pricing = service.pricing(user, "IGNORED-D01");
        var validation = service.validate(user);
        var preview = service.preview(user, "IGNORED-D01");
        assertThat(pricing.subtotal()).isEqualByComparingTo("320000");
        assertThat(preview.subtotal()).isEqualByComparingTo(pricing.subtotal());
        assertThat(preview.total()).isEqualByComparingTo(pricing.total());
        assertThat(preview.items().getFirst().unitPrice()).isEqualByComparingTo("160000");
        assertThat(preview.items().getFirst().lineTotal()).isEqualByComparingTo("320000");
        assertThat(validation.valid()).isFalse();
        assertThat(validation.issues()).isEqualTo(preview.issues());
        assertThat(validation.issues()).extracting(issue -> issue.code()).containsExactly(PRICE_CHANGED);
        assertThat(validation.issues().getFirst().itemId()).isEqualTo(line.getId());
        assertThat(validation.issues().getFirst().skuId()).isEqualTo(line.getSkuId());
        assertThat(line.getUnitPrice()).isEqualByComparingTo("150000");
        assertThat(line.getQuantity()).isEqualTo(2);
        assertThat(cart.getStatus()).isEqualTo(CartStatus.ACTIVE);
        verify(carts, times(3)).findByUserIdAndStatus(user.id(), CartStatus.ACTIVE);
        verify(items, times(3)).findByCart_Id(cart.getId());
        verifyNoMoreInteractions(carts, items);
    }

    @Test void twoTimes150000Has300000SubtotalAndScaleDifferencesAreNotPriceChanges() {
        var price = service.pricing(user, null);
        assertThat(price.subtotal()).isEqualByComparingTo("300000");
        assertThat(price.total()).isEqualByComparingTo("300000");
        assertThat(price.taxAmount()).isZero();
        assertThat(price.discountAmount()).isZero();
        assertThat(price.estimatedShippingFee()).isNull();
        assertThat(service.preview(user, null).shippingFee()).isNull();
        assertThat(service.validate(user).valid()).isTrue();
    }

    @Test void pricingAndPreviewTotalsAreSubtotalMinusDiscountPlusTaxPlusShipping() {
        var pricing = service.pricing(user, null);
        var preview = service.preview(user, null);

        assertThat(pricing.total()).isEqualByComparingTo(pricing.subtotal().subtract(pricing.discountAmount())
                .add(pricing.taxAmount()));
        assertThat(preview.total()).isEqualByComparingTo(preview.subtotal().subtract(preview.discountAmount())
                .add(preview.taxAmount()));
        assertThat(preview.total()).isEqualByComparingTo(pricing.total());
    }

    @Test void shippingStaysUnknownAboveFreeShippingThresholdUntilQuoteExists() {
        properties.setFreeShippingThreshold(new BigDecimal("100"));
        assertThat(service.pricing(user, null).estimatedShippingFee()).isNull();
        assertThat(service.preview(user, null).shippingFee()).isNull();
    }

    @Test void noActiveCartReturnsZeroPricingValidEmptyIssuesAndPreview400WithoutCreatingCart() {
        when(carts.findByUserIdAndStatus(user.id(), CartStatus.ACTIVE)).thenReturn(Optional.empty());
        assertThat(service.pricing(user, null).subtotal()).isZero();
        assertThat(service.validate(user).issues()).isEmpty();
        assertThat(service.validate(user).valid()).isTrue();
        assertCode(() -> service.preview(user, null), "COMMERCE_CART_EMPTY");
        verify(carts, times(4)).findByUserIdAndStatus(user.id(), CartStatus.ACTIVE);
        verifyNoMoreInteractions(carts);
        verifyNoInteractions(items, catalog);
    }

    @Test void existingEmptyCartAlsoRejectsPreview() {
        when(items.findByCart_Id(cart.getId())).thenReturn(List.of());
        assertCode(() -> service.preview(user, null), "COMMERCE_CART_EMPTY");
        verifyNoInteractions(catalog);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INACTIVE", "DRAFT"})
    void nonActiveProductIsReported(String status) {
        product(status);
        assertThat(service.validate(user).issues()).extracting(issue -> issue.code()).containsExactly(PRODUCT_INACTIVE);
    }

    @Test void catalog404ForHiddenInactiveProductIsAnIssueNotAnEndpoint404() {
        when(catalog.getProductById(line.getProductId())).thenThrow(mock(FeignException.NotFound.class));
        assertThat(service.preview(user, null).issues()).extracting(issue -> issue.code()).containsExactly(PRODUCT_INACTIVE);
    }

    @Test void missingSkuKeepsTheUnavailableLineVisibleAtItsLastSnapshotPrice() {
        when(catalog.getSkuById(line.getSkuId())).thenThrow(mock(FeignException.NotFound.class));
        var preview = service.preview(user, null);
        assertThat(preview.issues()).extracting(issue -> issue.code()).containsExactly(PRODUCT_INACTIVE);
        assertThat(preview.items()).hasSize(1);
        assertThat(preview.total()).isEqualByComparingTo("300000");
    }

    @Test void inactiveSkuAndPriceChangeCanBothBeReported() {
        sku.setStatus("INACTIVE");
        sku.setPrice(new BigDecimal("140000"));
        assertThat(service.validate(user).issues()).extracting(issue -> issue.code())
                .containsExactly(PRODUCT_INACTIVE, PRICE_CHANGED);
    }

    @Test void twoLinesOfTheSameProductUseOneProductReadAndBothContributeToSubtotal() {
        CartItem second = new CartItem();
        second.setId(UUID.randomUUID());
        second.setSkuId(UUID.randomUUID());
        second.setProductId(line.getProductId());
        second.setQuantity(3);
        second.setUnitPrice(new BigDecimal("10"));
        CatalogSkuResponse secondSku = new CatalogSkuResponse();
        secondSku.setId(second.getSkuId());
        secondSku.setProductId(second.getProductId());
        secondSku.setPrice(new BigDecimal("20"));
        secondSku.setStatus("ACTIVE");
        when(items.findByCart_Id(cart.getId())).thenReturn(List.of(second, line));
        when(catalog.getSkuById(second.getSkuId())).thenReturn(ApiResponse.of(secondSku));
        assertThat(service.preview(user, null).subtotal()).isEqualByComparingTo("300060");
        verify(catalog).getProductById(line.getProductId());
    }

    @Test void catalogFailureIsNotMistakenForValidCartOrOutOfStock() {
        when(catalog.getSkuById(line.getSkuId())).thenThrow(mock(FeignException.ServiceUnavailable.class));
        assertCode(() -> service.validate(user), "COMMERCE_CATALOG_UNAVAILABLE");
    }

    @Test void productLookupFailureIsAlsoNamedUnavailable() {
        when(catalog.getProductById(line.getProductId())).thenThrow(mock(FeignException.ServiceUnavailable.class));
        assertCode(() -> service.preview(user, null), "COMMERCE_CATALOG_UNAVAILABLE");
    }

    @Test void malformedSkuAndProductEnvelopesAreRejected() {
        when(catalog.getSkuById(line.getSkuId())).thenReturn(ApiResponse.of(null));
        assertCode(() -> service.pricing(user, null), "COMMERCE_INVALID_CATALOG_RESPONSE");
        when(catalog.getSkuById(line.getSkuId())).thenReturn(ApiResponse.of(sku));
        when(catalog.getProductById(line.getProductId())).thenReturn(ApiResponse.of(null));
        assertCode(() -> service.validate(user), "COMMERCE_INVALID_CATALOG_RESPONSE");
    }

    @Test void mismatchedSkuIdentityAndNegativePriceAreRejected() {
        sku.setId(UUID.randomUUID());
        assertCode(() -> service.validate(user), "COMMERCE_INVALID_CATALOG_RESPONSE");
        sku.setId(line.getSkuId());
        sku.setPrice(new BigDecimal("-1"));
        assertCode(() -> service.validate(user), "COMMERCE_INVALID_CATALOG_RESPONSE");
    }

    @Test void missingSellerIdentityCannotSilentlySkipMergeOwnershipChecks() {
        when(catalog.getProductById(line.getProductId())).thenReturn(ApiResponse.of(
                new CatalogClient.ProductResponse(line.getProductId(), null, "Product", "ACTIVE", List.of())));
        assertCode(() -> service.mergeIssues(user), "COMMERCE_INVALID_CATALOG_RESPONSE");
    }

    @Test void invalidStoredQuantityIsACommerceError() {
        line.setQuantity(0);
        assertCode(() -> service.preview(user, null), "COMMERCE_INVALID_QUANTITY");
    }

    @Test void unauthenticatedCallDoesNotReadOrWriteAnything() {
        assertCode(() -> service.pricing(null, null), "COMMERCE_UNAUTHENTICATED");
        assertCode(() -> service.validate(new CurrentUser(null, Set.of(), Set.of())), "COMMERCE_UNAUTHENTICATED");
        assertCode(() -> service.preview(null, null), "COMMERCE_UNAUTHENTICATED");
        verifyNoInteractions(carts, items, catalog);
    }

    @Test void disablingPriceRevalidationCannotProduceAStalePreview() {
        properties.setPriceRevalidation(false);
        assertCode(() -> service.preview(user, null), "COMMERCE_INVALID_PRICING_CONFIGURATION");
        verifyNoInteractions(carts, items, catalog);
    }

    private void product(String status) {
        when(catalog.getProductById(line.getProductId())).thenReturn(ApiResponse.of(
                new CatalogClient.ProductResponse(line.getProductId(), UUID.randomUUID(), "Product", status, List.of())));
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
