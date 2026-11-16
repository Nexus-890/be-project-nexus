package com.group5.commerceservice.cart;

import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.dto.CatalogSkuResponse;
import com.group5.commerceservice.cart.dto.request.CartItemRequest;
import com.group5.commerceservice.cart.dto.request.UpdateCartItemRequest;
import com.group5.commerceservice.cart.dto.response.CartIssue;
import com.group5.commerceservice.cart.dto.response.CartResponse;
import com.group5.commerceservice.cart.dto.response.GuestCartResponse;
import com.group5.commerceservice.cart.repository.CartItemRepository;
import com.group5.commerceservice.cart.repository.CartRepository;
import com.group5.commerceservice.cart.service.CartService;
import com.group5.commerceservice.shared.config.CartProperties;
import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(CartPersistenceTest.JpaConfig.class)
class GuestCartLifecycleIntegrationTest {
    @Autowired CartService service;
    @Autowired CartProperties properties;
    @Autowired CartRepository carts;
    @Autowired CartItemRepository items;
    @Autowired CatalogClient catalog;
    @Autowired DataSource dataSource;
    private UUID skuId;
    private UUID productId;
    private CurrentUser buyer;

    @AfterEach void restoreConfigurationAndActor() {
        properties.setAllowGuestCart(true);
        properties.setMergeOnLogin(true);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @BeforeEach void setup() {
        reset(catalog);
        properties.setAllowGuestCart(true);
        properties.setMergeOnLogin(true);
        buyer = buyer();
        skuId = UUID.randomUUID();
        productId = UUID.randomUUID();
        CatalogSkuResponse sku = new CatalogSkuResponse();
        sku.setId(skuId);
        sku.setProductId(productId);
        sku.setPrice(new BigDecimal("100.00"));
        sku.setStatus("ACTIVE");
        when(catalog.getSkuById(skuId)).thenReturn(ApiResponse.of(sku));
        when(catalog.getProductById(productId)).thenReturn(ApiResponse.of(
                new CatalogClient.ProductResponse(productId, UUID.randomUUID(), "Product", "ACTIVE", List.of())));
    }

    @Test void guestCanCreateReadAddUpdateRemoveAndCannotTouchAnotherOwnersItem() {
        GuestCartResponse guest = service.createGuestCart();
        assertThat(guest.cart().getUserId()).isNull();
        CartResponse added = service.addItem(null, guest.guestToken(), request(2));
        UUID itemId = added.getItems().getFirst().getId();
        assertThat(service.getMyCart(null, guest.guestToken()).getItems().getFirst().getQuantity()).isEqualTo(2);
        UpdateCartItemRequest update = new UpdateCartItemRequest();
        update.setQuantity(3);
        assertThat(service.updateCartItem(null, guest.guestToken(), itemId, update).getItems().getFirst().getQuantity()).isEqualTo(3);
        String other = service.createGuestCart().guestToken();
        assertCode(() -> service.updateCartItem(null, other, itemId, update), "COMMERCE_CART_NOT_FOUND");
        assertCode(() -> service.removeCartItem(null, other, itemId), "COMMERCE_CART_NOT_FOUND");
        service.removeCartItem(null, guest.guestToken(), itemId);
        assertThat(service.getMyCart(null, guest.guestToken()).getItems()).isEmpty();
    }

    @Test void quantitiesSumOnceAndGuestCannotBeUsedAfterMerge() {
        GuestCartResponse guest = guestWith(2);
        service.addItem(buyer, request(1));
        CartResponse merged = service.mergeGuestCart(buyer, guest.guestToken());
        assertThat(merged.getItems()).hasSize(1);
        assertThat(merged.getItems().getFirst().getQuantity()).isEqualTo(3);
        assertThat(carts.findById(guest.cart().getId()).orElseThrow().getStatus()).isEqualTo(CartStatus.MERGED);
        assertCode(() -> service.mergeGuestCart(buyer, guest.guestToken()), "COMMERCE_GUEST_CART_NOT_FOUND");
        assertCode(() -> service.getMyCart(null, guest.guestToken()), "COMMERCE_GUEST_CART_NOT_FOUND");
        assertCode(() -> service.addItem(null, guest.guestToken(), request(1)), "COMMERCE_GUEST_CART_NOT_FOUND");
        assertCode(() -> service.mergeGuestCart(buyer(), guest.guestToken()), "COMMERCE_GUEST_CART_NOT_FOUND");
    }

    @Test void mergeKeepsFiveWithoutReservingOrClampingToStock() {
        GuestCartResponse guest = guestWith(2);
        service.addItem(buyer, request(3));
        assertThat(service.mergeGuestCart(buyer, guest.guestToken()).getItems().getFirst().getQuantity()).isEqualTo(5);
        verify(catalog).getProductById(productId);
    }

    @Test void capIsVisibleAndAddAndUpdateRejectAmountsAbove999() {
        GuestCartResponse guest = guestWith(600);
        service.addItem(buyer, request(500));
        CartResponse merged = service.mergeGuestCart(buyer, guest.guestToken());
        assertThat(merged.getItems().getFirst().getQuantity()).isEqualTo(999);
        assertThat(merged.getIssues()).extracting(CartIssue::code).contains(CartIssue.Code.QUANTITY_CAPPED);
        assertCode(() -> service.addItem(buyer, request(1)), "COMMERCE_INVALID_QUANTITY");
        UpdateCartItemRequest update = new UpdateCartItemRequest();
        update.setQuantity(1000);
        assertCode(() -> service.updateCartItem(buyer, merged.getItems().getFirst().getId(), update), "COMMERCE_INVALID_QUANTITY");
        assertThat(service.getMyCart(buyer).getItems().getFirst().getQuantity()).isEqualTo(999);
    }

    @Test void invalidCatalogLinesAreRetainedAndFlagged() {
        GuestCartResponse guest = guestWith(2);
        when(catalog.getProductById(productId)).thenReturn(ApiResponse.of(
                new CatalogClient.ProductResponse(productId, buyer.id(), "Product", "INACTIVE", List.of())));
        CartResponse merged = service.mergeGuestCart(buyer, guest.guestToken());
        assertThat(merged.getItems()).hasSize(1);
        assertThat(merged.getIssues()).extracting(CartIssue::code)
                .contains(CartIssue.Code.PRODUCT_INACTIVE, CartIssue.Code.SELF_PURCHASE);
    }

    @Test void missingSkuIsRetainedAndFlaggedInsteadOfDiscardingTheGuestLine() {
        GuestCartResponse guest = guestWith(2);
        when(catalog.getSkuById(skuId)).thenThrow(feign.FeignException.errorStatus("getSkuById",
                feign.Response.builder().status(404).reason("Not found")
                        .request(feign.Request.create(feign.Request.HttpMethod.GET, "/sku", java.util.Map.of(),
                                (byte[]) null, java.nio.charset.StandardCharsets.UTF_8, null)).build()));
        CartResponse merged = service.mergeGuestCart(buyer, guest.guestToken());
        assertThat(merged.getItems().getFirst().getQuantity()).isEqualTo(2);
        assertThat(merged.getIssues()).extracting(CartIssue::code).contains(CartIssue.Code.PRODUCT_INACTIVE);
    }

    @Test void distinctSkusAreAppendedAndExistingBuyerPricesArePreserved() {
        GuestCartResponse guest = guestWith(2);
        service.addItem(buyer, request(3));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID targetId = service.getMyCart(buyer).getId();
        jdbc.update("UPDATE cart_items SET price = 90 WHERE cart_id = ?", targetId);
        UUID otherSku = UUID.randomUUID();
        jdbc.update("INSERT INTO cart_items (cart_id, product_id, sku_id, quantity, price) VALUES (?, ?, ?, 1, 25)",
                guest.cart().getId(), productId, otherSku);
        CatalogSkuResponse sku = new CatalogSkuResponse();
        sku.setId(otherSku);
        sku.setProductId(productId);
        sku.setPrice(new BigDecimal("25"));
        sku.setStatus("ACTIVE");
        when(catalog.getSkuById(otherSku)).thenReturn(ApiResponse.of(sku));
        CartResponse merged = service.mergeGuestCart(buyer, guest.guestToken());
        assertThat(merged.getItems()).hasSize(2);
        var existing = merged.getItems().stream().filter(item -> item.getSkuId().equals(skuId)).findFirst().orElseThrow();
        assertThat(existing.getQuantity()).isEqualTo(5);
        assertThat(existing.getUnitPrice()).isEqualByComparingTo("90");
        assertThat(merged.getIssues()).extracting(CartIssue::code).contains(CartIssue.Code.PRICE_CHANGED);
    }

    @Test void malformedCatalogRollsBackTheWholeMergeAndLeavesTokenRetryable() {
        GuestCartResponse guest = guestWith(2);
        service.addItem(buyer, request(3));
        var valid = catalog.getProductById(productId);
        when(catalog.getProductById(productId)).thenReturn(null);
        assertCode(() -> service.mergeGuestCart(buyer, guest.guestToken()), "COMMERCE_INVALID_CATALOG_RESPONSE");
        assertThat(service.getMyCart(buyer).getItems().getFirst().getQuantity()).isEqualTo(3);
        assertThat(service.getMyCart(null, guest.guestToken()).getItems().getFirst().getQuantity()).isEqualTo(2);
        when(catalog.getProductById(productId)).thenReturn(valid);
        assertThat(service.mergeGuestCart(buyer, guest.guestToken()).getItems().getFirst().getQuantity()).isEqualTo(5);
    }

    @Test void disabledFlagsRejectOperationsWithoutRetiringSource() {
        GuestCartResponse guest = guestWith(2);
        properties.setMergeOnLogin(false);
        assertCode(() -> service.mergeGuestCart(buyer, guest.guestToken()), "COMMERCE_CART_MERGE_DISABLED");
        properties.setAllowGuestCart(false);
        assertCode(service::createGuestCart, "COMMERCE_GUEST_CART_DISABLED");
        assertCode(() -> service.getMyCart(null, guest.guestToken()), "COMMERCE_GUEST_CART_DISABLED");
        assertThat(carts.findById(guest.cart().getId()).orElseThrow().getStatus()).isEqualTo(CartStatus.ACTIVE);
        assertThat(service.getMyCart(buyer).getUserId()).isEqualTo(buyer.id());
    }

    @Test void missingSellerRollsBackNewBuyerCartAndKeepsGuestRetryable() {
        GuestCartResponse guest = guestWith(2);
        var valid = catalog.getProductById(productId);
        when(catalog.getProductById(productId)).thenReturn(ApiResponse.of(
                new CatalogClient.ProductResponse(productId, null, "Product", "ACTIVE", List.of())));

        assertCode(() -> service.mergeGuestCart(buyer, guest.guestToken()), "COMMERCE_INVALID_CATALOG_RESPONSE");
        assertThat(carts.findByUserIdAndStatus(buyer.id(), CartStatus.ACTIVE)).isEmpty();
        assertThat(service.getMyCart(null, guest.guestToken()).getItems().getFirst().getQuantity()).isEqualTo(2);

        when(catalog.getProductById(productId)).thenReturn(valid);
        assertThat(service.mergeGuestCart(buyer, guest.guestToken()).getItems().getFirst().getQuantity()).isEqualTo(2);
    }

    @Test void catalogOutageRollsBackAllMergedLinesAndAllowsExactlyOneRetry() {
        GuestCartResponse guest = guestWith(2);
        service.addItem(buyer, request(3));
        UUID otherSku = UUID.randomUUID();
        new JdbcTemplate(dataSource).update(
                "INSERT INTO cart_items (cart_id, product_id, sku_id, quantity, price) VALUES (?, ?, ?, 4, 25)",
                guest.cart().getId(), productId, otherSku);
        when(catalog.getSkuById(otherSku)).thenThrow(feign.FeignException.errorStatus("getSkuById",
                feign.Response.builder().status(503).reason("Unavailable")
                        .request(feign.Request.create(feign.Request.HttpMethod.GET, "/sku", java.util.Map.of(),
                                (byte[]) null, java.nio.charset.StandardCharsets.UTF_8, null)).build()));

        assertCode(() -> service.mergeGuestCart(buyer, guest.guestToken()), "COMMERCE_CATALOG_UNAVAILABLE");
        assertThat(service.getMyCart(buyer).getItems()).singleElement()
                .satisfies(item -> assertThat(item.getQuantity()).isEqualTo(3));
        assertThat(service.getMyCart(null, guest.guestToken()).getItems()).hasSize(2);
        assertThat(carts.findById(guest.cart().getId()).orElseThrow().getStatus()).isEqualTo(CartStatus.ACTIVE);

        CatalogSkuResponse recovered = new CatalogSkuResponse();
        recovered.setId(otherSku);
        recovered.setProductId(productId);
        recovered.setPrice(new java.math.BigDecimal("25"));
        recovered.setStatus("ACTIVE");
        doReturn(ApiResponse.of(recovered)).when(catalog).getSkuById(otherSku);
        CartResponse merged = service.mergeGuestCart(buyer, guest.guestToken());
        assertThat(merged.getItems()).hasSize(2);
        assertThat(merged.getItems()).filteredOn(item -> item.getSkuId().equals(skuId)).singleElement()
                .satisfies(item -> assertThat(item.getQuantity()).isEqualTo(5));
        assertThat(merged.getItems()).filteredOn(item -> item.getSkuId().equals(otherSku)).singleElement()
                .satisfies(item -> assertThat(item.getQuantity()).isEqualTo(4));
        assertCode(() -> service.mergeGuestCart(buyer, guest.guestToken()), "COMMERCE_GUEST_CART_NOT_FOUND");
    }

    @Test void viewingDoesNotExtendExpiryAndExpiredGuestCannotMerge() {
        GuestCartResponse guest = guestWith(2);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE carts SET updated_at = ? WHERE id = ?",
                OffsetDateTime.now().minusDays(6), guest.cart().getId());
        var before = carts.findById(guest.cart().getId()).orElseThrow().getUpdatedAt();
        service.getMyCart(null, guest.guestToken());
        assertThat(carts.findById(guest.cart().getId()).orElseThrow().getUpdatedAt()).isEqualTo(before);
        // Match CartService's JVM clock and stay clear of the expiry boundary.
        jdbc.update("UPDATE carts SET updated_at = ? WHERE id = ?",
                OffsetDateTime.now().minusDays(7).minusSeconds(1), guest.cart().getId());
        assertCode(() -> service.getMyCart(null, guest.guestToken()), "COMMERCE_GUEST_CART_NOT_FOUND");
        assertCode(() -> service.mergeGuestCart(buyer, guest.guestToken()), "COMMERCE_GUEST_CART_NOT_FOUND");
    }

    @Test void bearerOwnerTakesPrecedenceOverGuestHeaderAndEmptyGuestCanMerge() {
        GuestCartResponse guest = guestWith(2);
        service.addItem(buyer, guest.guestToken(), request(3));
        assertThat(service.getMyCart(buyer, "invalid").getItems().getFirst().getQuantity()).isEqualTo(3);
        assertThat(service.getMyCart(null, guest.guestToken()).getItems().getFirst().getQuantity()).isEqualTo(2);
        GuestCartResponse empty = service.createGuestCart();
        assertThat(service.mergeGuestCart(buyer(), empty.guestToken()).getItems()).isEmpty();
        assertThat(carts.findById(empty.cart().getId()).orElseThrow().getStatus()).isEqualTo(CartStatus.MERGED);
    }

    @Test void concurrentMergeOfTheSameTokenSucceedsExactlyOnce() throws Exception {
        GuestCartResponse guest = guestWith(2);
        service.addItem(buyer, request(1));
        List<String> results = race(() -> mergeOutcome(guest.guestToken()), () -> mergeOutcome(guest.guestToken()));
        assertThat(results).containsExactlyInAnyOrder("OK", "COMMERCE_GUEST_CART_NOT_FOUND");
        assertThat(service.getMyCart(buyer).getItems().getFirst().getQuantity()).isEqualTo(3);
    }

    @Test void concurrentDifferentBuyersCannotBothClaimTheSameGuestCart() throws Exception {
        GuestCartResponse guest = guestWith(2);
        CurrentUser otherBuyer = buyer();
        List<String> results = race(() -> mergeOutcome(buyer, guest.guestToken()),
                () -> mergeOutcome(otherBuyer, guest.guestToken()));
        assertThat(results).containsExactlyInAnyOrder("OK", "COMMERCE_GUEST_CART_NOT_FOUND");
        CurrentUser winner = results.getFirst().equals("OK") ? buyer : otherBuyer;
        CurrentUser loser = results.getFirst().equals("OK") ? otherBuyer : buyer;
        assertThat(carts.findByUserIdAndStatus(loser.id(), CartStatus.ACTIVE)).isEmpty();
        assertThat(service.getMyCart(winner).getItems()).singleElement()
                .satisfies(item -> assertThat(item.getQuantity()).isEqualTo(2));
        assertThat(carts.findById(guest.cart().getId()).orElseThrow().getStatus()).isEqualTo(CartStatus.MERGED);
    }

    @Test void twoGuestsMergingIntoTheSameNewUserCartDoNotLoseQuantities() throws Exception {
        GuestCartResponse first = guestWith(2);
        GuestCartResponse second = guestWith(3);
        assertThat(race(() -> mergeOutcome(first.guestToken()), () -> mergeOutcome(second.guestToken())))
                .containsExactly("OK", "OK");
        assertThat(service.getMyCart(buyer).getItems()).hasSize(1);
        assertThat(service.getMyCart(buyer).getItems().getFirst().getQuantity()).isEqualTo(5);
    }

    @Test void guestAddRacingWithMergeEitherCommitsBeforeMergeOrReturnsNotFound() throws Exception {
        GuestCartResponse guest = guestWith(2);
        List<String> results = race(() -> mergeOutcome(guest.guestToken()), () -> {
            try { service.addItem(null, guest.guestToken(), request(1)); return "OK"; }
            catch (DomainException exception) { return exception.getErrorCode(); }
        });
        assertThat(results.getFirst()).isEqualTo("OK");
        assertThat(results.getLast()).isIn("OK", "COMMERCE_GUEST_CART_NOT_FOUND");
        assertThat(service.getMyCart(buyer).getItems().getFirst().getQuantity())
                .isEqualTo(results.getLast().equals("OK") ? 3 : 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"update", "remove"})
    void guestEditsRacingWithMergeAreAppliedBeforeMergeOrRejectedAfterIt(String operation) throws Exception {
        GuestCartResponse guest = guestWith(2);
        UUID itemId = service.getMyCart(null, guest.guestToken()).getItems().getFirst().getId();
        List<String> results = race(() -> mergeOutcome(guest.guestToken()), () -> {
            try {
                if (operation.equals("update")) {
                    UpdateCartItemRequest request = new UpdateCartItemRequest();
                    request.setQuantity(5);
                    service.updateCartItem(null, guest.guestToken(), itemId, request);
                } else {
                    service.removeCartItem(null, guest.guestToken(), itemId);
                }
                return "OK";
            } catch (DomainException exception) {
                return exception.getErrorCode();
            }
        });
        assertThat(results.getFirst()).isEqualTo("OK");
        assertThat(results.getLast()).isIn("OK", "COMMERCE_GUEST_CART_NOT_FOUND");
        int expected = results.getLast().equals("OK") ? (operation.equals("update") ? 5 : 0) : 2;
        assertThat(service.getMyCart(buyer).getItems().stream().mapToInt(item -> item.getQuantity()).sum()).isEqualTo(expected);
    }

    @Test void memberAddRacingWithMergeRetainsBothCommittedQuantities() throws Exception {
        GuestCartResponse guest = guestWith(2);
        service.addItem(buyer, request(3));
        assertThat(race(() -> mergeOutcome(guest.guestToken()), () -> {
            service.addItem(buyer, request(1));
            return "OK";
        })).containsExactly("OK", "OK");
        assertThat(service.getMyCart(buyer).getItems()).singleElement()
                .satisfies(item -> assertThat(item.getQuantity()).isEqualTo(6));
    }

    private String mergeOutcome(String token) {
        return mergeOutcome(buyer, token);
    }

    private String mergeOutcome(CurrentUser user, String token) {
        try { service.mergeGuestCart(user, token); return "OK"; }
        catch (DomainException exception) { return exception.getErrorCode(); }
    }

    private List<String> race(Callable<String> first, Callable<String> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(action -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Race did not start");
                return action.call();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(futures.getFirst().get(30, TimeUnit.SECONDS), futures.getLast().get(30, TimeUnit.SECONDS));
        }
    }

    private GuestCartResponse guestWith(int quantity) {
        GuestCartResponse guest = service.createGuestCart();
        service.addItem(null, guest.guestToken(), request(quantity));
        return guest;
    }

    private CartItemRequest request(int quantity) {
        CartItemRequest request = new CartItemRequest();
        request.setSkuId(skuId);
        request.setQuantity(quantity);
        return request;
    }

    private static CurrentUser buyer() { return new CurrentUser(UUID.randomUUID(), Set.of("BUYER"), Set.of("CART.UPDATE")); }
    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
