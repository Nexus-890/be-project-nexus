package com.group5.commerceservice.cart;

import com.group5.commerceservice.cart.controller.CartController;
import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.dto.CatalogSkuResponse;
import com.group5.commerceservice.cart.repository.CartRepository;
import com.group5.commerceservice.cart.service.CartPricingService;
import com.group5.commerceservice.cart.service.CartService;
import com.group5.commerceservice.shared.config.CartProperties;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.autoconfigure.SecurityAutoConfiguration;
import com.group5.common_web.exception.GlobalExceptionHandler;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real security filters, MVC and PostgreSQL; only JWT verification and catalog HTTP are stand-ins. */
@SpringJUnitConfig(GuestCartWebPersistenceTest.Config.class)
@ContextConfiguration(initializers = GuestCartSecurityTest.ServiceYaml.class)
@WebAppConfiguration
class GuestCartWebPersistenceTest {
    private static final UUID USER = UUID.randomUUID();
    private static final UUID SKU = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    @Autowired WebApplicationContext context;
    @Autowired CatalogClient catalog;
    @Autowired CartProperties properties;
    @Autowired CartRepository carts;
    private MockMvc mvc;

    @BeforeEach void setup() {
        reset(catalog);
        properties.setAllowGuestCart(true);
        properties.setMergeOnLogin(true);
        properties.setGuestTokenSecret(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        CatalogSkuResponse sku = new CatalogSkuResponse();
        sku.setId(SKU);
        sku.setProductId(PRODUCT);
        sku.setPrice(new BigDecimal("100.00"));
        sku.setStatus("ACTIVE");
        when(catalog.getSkuById(SKU)).thenReturn(ApiResponse.of(sku));
        when(catalog.getProductById(PRODUCT)).thenReturn(ApiResponse.of(
                new CatalogClient.ProductResponse(PRODUCT, UUID.randomUUID(), "Product", "ACTIVE", List.of())));
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test void anonymousCartSurvivesReloadThenMergesOnceIntoTheAuthenticatedCart() throws Exception {
        String created = mvc.perform(post("/api/v1/carts/guest"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.cart.userId").value(nullValue()))
                .andExpect(jsonPath("$.data.cart.items").isEmpty())
                .andReturn().getResponse().getContentAsString();
        String guestToken = JsonPath.read(created, "$.data.guestToken");
        UUID sourceId = UUID.fromString(JsonPath.read(created, "$.data.cart.id"));
        mvc.perform(post("/api/v1/carts/me/items").header("X-Guest-Token", guestToken)
                        .contentType(MediaType.APPLICATION_JSON).content(itemBody(2)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.items[0].quantity").value(2));
        mvc.perform(get("/api/v1/carts/me").header("X-Guest-Token", guestToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].quantity").value(2));

        // An invalid bearer must never gain anonymous access through a valid guest token.
        mvc.perform(post("/api/v1/carts/me/items").header("X-Guest-Token", guestToken)
                        .header("Authorization", "Bearer invalid")
                        .contentType(MediaType.APPLICATION_JSON).content(itemBody(100)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/carts/me/items").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(itemBody(3)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.items[0].quantity").value(3));

        mvc.perform(post("/api/v1/carts/me/merge").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"guestToken\":\"" + guestToken + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(USER.toString()))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].quantity").value(5))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(100))
                .andExpect(jsonPath("$.data.issues").isEmpty());
        assertThat(carts.findById(sourceId).orElseThrow().getStatus()).isEqualTo(CartStatus.MERGED);

        mvc.perform(get("/api/v1/carts/me").header("X-Guest-Token", guestToken))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COMMERCE_GUEST_CART_NOT_FOUND"));
        mvc.perform(post("/api/v1/carts/me/merge").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"guestToken\":\"" + guestToken + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COMMERCE_GUEST_CART_NOT_FOUND"));
        mvc.perform(get("/api/v1/carts/me").header("Authorization", "Bearer buyer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].quantity").value(5));
    }

    private static String itemBody(int quantity) {
        return "{\"skuId\":\"" + SKU + "\",\"quantity\":" + quantity + "}";
    }

    @Configuration(proxyBeanMethods = false)
    @Import(CartPersistenceTest.JpaConfig.class)
    @EnableWebMvc
    @EnableWebSecurity
    @ImportAutoConfiguration(SecurityAutoConfiguration.class)
    static class Config {
        @Bean CartController controller(CartService service, CartPricingService pricing) {
            return new CartController(service, pricing);
        }
        @Bean GlobalExceptionHandler handler() { return new GlobalExceptionHandler(null); }
        @Bean JwtDecoder decoder() {
            return value -> {
                if (!value.equals("buyer")) throw new BadJwtException("Invalid JWT");
                return Jwt.withTokenValue(value).header("alg", "RS256").subject(USER.toString())
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                        .claim("roles", List.of("BUYER"))
                        .claim("privileges", List.of("CART.VIEW", "CART.CREATE", "CART.UPDATE", "CART.REMOVE_ITEM"))
                        .build();
            };
        }
    }
}
