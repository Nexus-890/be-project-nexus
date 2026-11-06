package com.group5.commerceservice.cart;

import com.group5.commerceservice.cart.controller.CartController;
import com.group5.commerceservice.cart.service.CartPricingService;
import com.group5.commerceservice.cart.service.CartService;
import com.group5.commerceservice.checkout.controller.CheckoutController;
import com.group5.commerceservice.checkout.dto.CheckoutPreviewRequest;
import com.group5.common_security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(CartPricingSecurityTest.Config.class)
class CartPricingSecurityTest {
    @Autowired private CartController carts;
    @Autowired private CheckoutController checkout;
    @Autowired private CartPricingService pricing;

    @AfterEach void clear() {
        SecurityContextHolder.clearContext();
        reset(pricing);
    }

    @Test void cartViewAloneAllowsPricingAndValidationWithoutCartCreateOrRoleGate() {
        CurrentUser user = authenticate("CART.VIEW");
        carts.pricing(user, null);
        carts.validate(user);
        verify(pricing).pricing(user, null);
        verify(pricing).validate(user);
    }

    @Test void checkoutStartAloneAllowsPreviewWithoutOrderCreate() {
        CurrentUser user = authenticate("CHECKOUT.START");
        checkout.preview(user, new CheckoutPreviewRequest(null, "SEPAY", "D01"));
        verify(pricing).preview(user, "D01");
    }

    @Test void adminHasNoImplicitPrivilegeBypass() {
        CurrentUser user = authenticate("ROLE_ADMIN");
        assertThatThrownBy(() -> carts.pricing(user, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> carts.validate(user)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> checkout.preview(user, new CheckoutPreviewRequest(null, "SEPAY", null)))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(pricing);
    }

    private static CurrentUser authenticate(String... privileges) {
        CurrentUser user = new CurrentUser(UUID.randomUUID(), Set.of(), Set.of(privileges));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user, "", AuthorityUtils.createAuthorityList(privileges)));
        return user;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Config {
        @Bean CartPricingService pricing() { return mock(CartPricingService.class); }
        @Bean CartController carts(CartPricingService pricing) { return new CartController(mock(CartService.class), pricing); }
        @Bean CheckoutController checkout(CartPricingService pricing) { return new CheckoutController(pricing); }
    }
}
