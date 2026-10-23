package com.group5.commerceservice.cart;

import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.entity.Cart;
import com.group5.commerceservice.cart.entity.CartItem;
import com.group5.commerceservice.cart.repository.CartItemRepository;
import com.group5.commerceservice.cart.repository.CartRepository;
import com.group5.commerceservice.cart.request.CartItemRequest;
import com.group5.commerceservice.cart.service.CartService;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in HTTP proof; start infra/contract-simulation/catalog-envelope.sim.yaml first. */
@EnabledIfEnvironmentVariable(named = "CATALOG_CONTRACT_SIMULATION_URL", matches = ".+")
class CatalogEnvelopeHttpTest {
    private static final UUID SKU = UUID.fromString("c8000000-0000-4000-8000-000000000003");
    private static final UUID PRODUCT = UUID.fromString("c8000000-0000-4000-8000-000000000002");
    private static final UUID USER = UUID.fromString("68000000-0000-4000-8000-000000000002");
    private static final BigDecimal PRICE = new BigDecimal("150000.5");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ClientConfiguration.class)
            .withPropertyValues(
                    "spring.cloud.openfeign.client.config.catalog-service.url="
                            + System.getenv("CATALOG_CONTRACT_SIMULATION_URL"),
                    "spring.cloud.openfeign.client.config.catalog-service.connectTimeout=2000",
                    "spring.cloud.openfeign.client.config.catalog-service.readTimeout=2000");

    @Test void wrappedSkuDecodesThroughRealFeignAndUpdatesCart() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            CatalogClient client = context.getBean(CatalogClient.class);
            var decoded = client.getSkuById(SKU);
            assertThat(decoded.data().getId()).isEqualTo(SKU);
            assertThat(decoded.data().getProductId()).isEqualTo(PRODUCT);
            assertThat(decoded.data().getPrice()).isEqualByComparingTo(PRICE);

            CartRepository carts = mock(CartRepository.class);
            CartItemRepository items = mock(CartItemRepository.class);
            Cart cart = new Cart();
            cart.setId(UUID.randomUUID());
            cart.setUserId(USER);
            cart.setStatus("ACTIVE");
            when(carts.findByUserIdAndStatus(USER, "ACTIVE")).thenReturn(Optional.of(cart));
            when(carts.touchActiveCart(cart.getId())).thenReturn(1);
            // Capture the actual write and expose its result to the service's readback.
            doAnswer(invocation -> {
                CartItem item = new CartItem();
                item.setId(invocation.getArgument(0));
                item.setProductId(invocation.getArgument(2));
                item.setSkuId(invocation.getArgument(3));
                item.setQuantity(invocation.getArgument(4));
                item.setUnitPrice(invocation.getArgument(5));
                when(items.findByCart_Id(cart.getId())).thenReturn(List.of(item));
                return null;
            }).when(items).addOrIncreaseQuantity(any(), any(), any(), any(), anyInt(), any());

            var result = new CartService(carts, items, client).addItem(user(), request(SKU));

            assertThat(result.getItems()).hasSize(1);
            assertThat(result.getItems().getFirst().getSkuId()).isEqualTo(SKU);
            assertThat(result.getItems().getFirst().getUnitPrice()).isEqualByComparingTo(PRICE);
            verify(items).addOrIncreaseQuantity(any(), eq(cart.getId()), eq(PRODUCT), eq(SKU), eq(2), eq(PRICE));
        });
    }

    @Test void catalog404BecomesNamedSkuNotFoundWithoutCartWrites() {
        assertFailure("c8000000-0000-4000-8000-000000000004", "COMMERCE_SKU_NOT_FOUND", 404);
    }

    @Test void catalog503BecomesNamedUnavailableWithoutCartWrites() {
        assertFailure("c8000000-0000-4000-8000-000000000005", "COMMERCE_CATALOG_UNAVAILABLE", 503);
    }

    @Test void missingDataIsRejectedWithoutCartWrites() {
        assertFailure("c8000000-0000-4000-8000-000000000006", "COMMERCE_INVALID_CATALOG_RESPONSE", 502);
    }

    private void assertFailure(String sku, String code, int status) {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            CartRepository carts = mock(CartRepository.class);
            CartItemRepository items = mock(CartItemRepository.class);
            CartService service = new CartService(carts, items, context.getBean(CatalogClient.class));
            DomainException failure = assertThrows(DomainException.class,
                    () -> service.addItem(user(), request(UUID.fromString(sku))));
            assertThat(failure.getErrorCode()).isEqualTo(code);
            assertThat(failure.getCode()).isEqualTo(status);
            verifyNoInteractions(carts, items);
        });
    }

    private static CurrentUser user() { return new CurrentUser(USER, Set.of(), Set.of()); }
    private static CartItemRequest request(UUID sku) {
        CartItemRequest request = new CartItemRequest();
        request.setSkuId(sku);
        request.setQuantity(2);
        return request;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = CatalogClient.class)
    @Import(FeignAutoConfiguration.class)
    static class ClientConfiguration { }
}
