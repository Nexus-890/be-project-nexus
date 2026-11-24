package com.group5.commerceservice.order;

import com.group5.commerceservice.order.summary.OrderSummaryController;
import com.group5.commerceservice.order.summary.OrderSummaryService;
import com.group5.common_security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringJUnitConfig(OrderSummaryControllerSecurityTest.SecurityConfig.class)
class OrderSummaryControllerSecurityTest {

    @Autowired private OrderSummaryController controller;
    @Autowired private OrderSummaryService summaries;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        Mockito.reset(summaries);
    }

    @Test
    void managersWithOrderListReachTheManagedSummary() {
        for (String role : Set.of("ADMIN", "SUPPORT_STAFF")) {
            CurrentUser manager = authenticate(Set.of(role), "ROLE_" + role, "ORDER.LIST");
            UUID sellerId = UUID.randomUUID();

            controller.managed(manager, sellerId, 7);

            verify(summaries).managed(manager, sellerId, 7);
        }
    }

    @Test
    void aSellerWithOrderListReachesTheSalesSummary() {
        CurrentUser seller = authenticate(Set.of("SELLER"), "ROLE_SELLER", "ORDER.LIST");

        controller.sales(seller, 30);

        verify(summaries).sales(seller, 30);
    }

    @Test
    void wrongRolesOrMissingOrderListStopBeforeTheService() {
        CurrentUser buyer = authenticate(Set.of("BUYER"), "ROLE_BUYER", "ORDER.LIST");
        assertThatThrownBy(() -> controller.managed(buyer, null, 30)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.sales(buyer, 30)).isInstanceOf(AccessDeniedException.class);

        CurrentUser seller = authenticate(Set.of("SELLER"), "ROLE_SELLER", "ORDER.LIST");
        assertThatThrownBy(() -> controller.managed(seller, null, 30)).isInstanceOf(AccessDeniedException.class);

        CurrentUser admin = authenticate(Set.of("ADMIN"), "ROLE_ADMIN", "ORDER.LIST");
        assertThatThrownBy(() -> controller.sales(admin, 30)).isInstanceOf(AccessDeniedException.class);

        CurrentUser adminWithoutList = authenticate(Set.of("ADMIN"), "ROLE_ADMIN", "ORDER.VIEW");
        assertThatThrownBy(() -> controller.managed(adminWithoutList, null, 30)).isInstanceOf(AccessDeniedException.class);

        CurrentUser sellerWithoutList = authenticate(Set.of("SELLER"), "ROLE_SELLER", "ORDER.VIEW");
        assertThatThrownBy(() -> controller.sales(sellerWithoutList, 30)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(summaries);
    }

    private static CurrentUser authenticate(Set<String> roles, String... authorities) {
        CurrentUser user = new CurrentUser(UUID.randomUUID(), roles, Set.of());
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user, "", AuthorityUtils.createAuthorityList(authorities)));
        return user;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class SecurityConfig {
        @Bean
        OrderSummaryService orderSummaryService() {
            return Mockito.mock(OrderSummaryService.class);
        }

        @Bean
        OrderSummaryController orderSummaryController(OrderSummaryService summaries) {
            return new OrderSummaryController(summaries);
        }
    }
}
