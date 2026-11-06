package com.group5.commerceservice.cart;

import com.group5.commerceservice.cart.controller.CartController;
import com.group5.commerceservice.cart.dto.response.CartResponse;
import com.group5.commerceservice.cart.dto.response.GuestCartResponse;
import com.group5.commerceservice.cart.service.CartPricingService;
import com.group5.commerceservice.cart.service.CartService;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.autoconfigure.SecurityAutoConfiguration;
import com.group5.common_web.exception.GlobalExceptionHandler;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.FileSystemResource;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(GuestCartSecurityTest.Config.class)
@ContextConfiguration(initializers = GuestCartSecurityTest.ServiceYaml.class)
@WebAppConfiguration
class GuestCartSecurityTest {
    private static final UUID USER = UUID.randomUUID();
    private static final UUID CART = UUID.randomUUID();
    @Autowired WebApplicationContext context;
    @Autowired CartService service;
    private MockMvc mvc;

    @BeforeEach void setup() {
        reset(service);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test void anonymousGuestCreationAndHeaderOperationsUseTheSuccessContract() throws Exception {
        CartResponse cart = new CartResponse(CART, null, "ACTIVE", List.of());
        when(service.createGuestCart()).thenReturn(new GuestCartResponse("owner-token", cart));
        when(service.getMyCart(isNull(), eq("owner-token"))).thenReturn(cart);
        when(service.addItem(isNull(), eq("owner-token"), any())).thenReturn(cart);
        when(service.updateCartItem(isNull(), eq("owner-token"), any(), any())).thenReturn(cart);
        mvc.perform(post("/api/v1/carts/guest")).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.guestToken").value("owner-token"));
        mvc.perform(get("/api/v1/carts/me").header("X-Guest-Token", "owner-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(CART.toString()));
        mvc.perform(post("/api/v1/carts/me/items").header("X-Guest-Token", "owner-token")
                        .contentType(MediaType.APPLICATION_JSON).content(itemBody(1)))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/v1/carts/me/items/" + UUID.randomUUID()).header("X-Guest-Token", "owner-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":3}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/carts/me/items/" + UUID.randomUUID()).header("X-Guest-Token", "owner-token"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(service).removeCartItem(isNull(), eq("owner-token"), any());
    }

    @Test void realJwtPrincipalResolvesToCurrentUserAndCannotBypassPrivilegesWithGuestHeader() throws Exception {
        when(service.getMyCart(any(), any())).thenReturn(new CartResponse(CART, USER, "ACTIVE", List.of()));
        mvc.perform(get("/api/v1/carts/me").header("Authorization", "Bearer buyer")
                        .header("X-Guest-Token", "guest"))
                .andExpect(status().isOk());
        verify(service).getMyCart(argThat(user -> user != null && USER.equals(user.id())), eq("guest"));
        clearInvocations(service);
        mvc.perform(post("/api/v1/carts/me/items").header("Authorization", "Bearer no-privilege")
                        .header("X-Guest-Token", "guest").contentType(MediaType.APPLICATION_JSON).content(itemBody(1)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/carts/me").header("Authorization", "Bearer view-only"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void invalidBearerDoesNotFallBackToGuestAndMergeRequiresBuyerAndUpdate() throws Exception {
        mvc.perform(get("/api/v1/carts/me").header("Authorization", "Bearer invalid").header("X-Guest-Token", "guest"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/carts/me/merge").contentType(MediaType.APPLICATION_JSON).content("{\"guestToken\":\"guest\"}"))
                .andExpect(status().isUnauthorized());
        for (String token : List.of("no-privilege", "seller-update")) {
            mvc.perform(post("/api/v1/carts/me/merge").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"guestToken\":\"guest\"}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
        when(service.mergeGuestCart(any(), eq("guest"))).thenReturn(new CartResponse(CART, USER, "ACTIVE", List.of()));
        mvc.perform(post("/api/v1/carts/me/merge").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"guestToken\":\"guest\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.issues").isArray());
    }

    @Test void quantityBoundsAndMergeBodyAreValidatedBeforeServiceCalls() throws Exception {
        mvc.perform(post("/api/v1/carts/me/items").contentType(MediaType.APPLICATION_JSON).content(itemBody(1000)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/carts/me/merge").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"guestToken\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    @Test void disabledGuestAndReplayedMergeReturnNamedProblemDetails() throws Exception {
        when(service.createGuestCart()).thenThrow(new DomainException(CommerceErrorCode.GUEST_CART_DISABLED));
        mvc.perform(post("/api/v1/carts/guest")).andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COMMERCE_GUEST_CART_DISABLED"));
        when(service.mergeGuestCart(any(), eq("merged"))).thenThrow(new DomainException(CommerceErrorCode.GUEST_CART_NOT_FOUND));
        mvc.perform(post("/api/v1/carts/me/merge").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"guestToken\":\"merged\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COMMERCE_GUEST_CART_NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value("/api/v1/carts/me/merge"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "\"2\"", "true", "2147483648"})
    void nonIntegerQuantityBodiesNeverReachTheCartService(String quantity) throws Exception {
        mvc.perform(post("/api/v1/carts/me/items").header("X-Guest-Token", "owner-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":\"" + UUID.randomUUID() + "\",\"quantity\":" + quantity + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/carts/me/items/" + UUID.randomUUID()).header("X-Guest-Token", "owner-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":" + quantity + "}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private static String itemBody(int quantity) { return "{\"skuId\":\"" + UUID.randomUUID() + "\",\"quantity\":" + quantity + "}"; }

    static class ServiceYaml implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        public void initialize(ConfigurableApplicationContext context) {
            var yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new FileSystemResource("../config-service/src/main/resources/config/commerce-service.yml"));
            context.getEnvironment().getPropertySources().addFirst(new PropertiesPropertySource("service-yaml", yaml.getObject()));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @ImportAutoConfiguration(SecurityAutoConfiguration.class)
    static class Config {
        @Bean CartService service() { return mock(CartService.class); }
        @Bean CartController controller(CartService service) { return new CartController(service, mock(CartPricingService.class)); }
        @Bean GlobalExceptionHandler handler() { return new GlobalExceptionHandler(null); }
        @Bean JwtDecoder decoder() {
            return value -> {
                if (value.equals("invalid")) throw new BadJwtException("Invalid JWT");
                List<String> privileges = switch (value) {
                    case "buyer" -> List.of("CART.VIEW", "CART.CREATE", "CART.UPDATE", "CART.REMOVE_ITEM");
                    case "seller-update" -> List.of("CART.UPDATE");
                    case "view-only" -> List.of("CART.VIEW");
                    default -> List.of();
                };
                return Jwt.withTokenValue(value).header("alg", "RS256").subject(USER.toString())
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                        .claim("roles", value.equals("seller-update") ? List.of("SELLER") : List.of("BUYER"))
                        .claim("privileges", privileges).build();
            };
        }
    }
}
