package com.group5.commerceservice.order;

import com.group5.commerceservice.order.controller.OrderController;
import com.group5.commerceservice.order.dto.request.CancelOrderRequest;
import com.group5.commerceservice.order.dto.request.ChangeOrderStatusRequest;
import com.group5.commerceservice.order.service.OrderService;
import com.group5.common_security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(OrderControllerSecurityTest.SecurityConfig.class)
class OrderControllerSecurityTest {

    private static final ChangeOrderStatusRequest PAID = new ChangeOrderStatusRequest(OrderStatus.PAID, "Paid");

    @Autowired private OrderController controller;
    @Autowired private OrderService orderService;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        Mockito.reset(orderService);
    }

    @Test
    void aBuyerCannotChangeTheStatus() {
        CurrentUser buyer = authenticate(Set.of("BUYER"), "ROLE_BUYER", "ORDER.VIEW");
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> controller.changeStatus(buyer, orderId, PAID))
                .isInstanceOf(AccessDeniedException.class);
        verify(orderService, never()).changeStatus(any(), any(), any());
    }

    @Test
    void anAdminReachesTheService() {
        CurrentUser admin = authenticate(Set.of("ADMIN"), "ROLE_ADMIN");
        UUID orderId = UUID.randomUUID();

        controller.changeStatus(admin, orderId, PAID);

        verify(orderService).changeStatus(admin, orderId, PAID);
    }

    @Test
    void historyNeedsOrderView() {
        CurrentUser caller = authenticate(Set.of("BUYER"), "ROLE_BUYER");

        assertThatThrownBy(() -> controller.statusHistory(caller, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
        verify(orderService, never()).statusHistory(any(), any());
    }

    @Test
    void readingOneOrderNeedsOrderView() {
        CurrentUser caller = authenticate(Set.of("BUYER"), "ROLE_BUYER", "ORDER.LIST");

        assertThatThrownBy(() -> controller.getOrder(caller, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
        verify(orderService, never()).getOrder(any(), any());
    }

    @Test
    void listingOrdersNeedsOrderList() {
        CurrentUser caller = authenticate(Set.of("BUYER"), "ROLE_BUYER", "ORDER.VIEW");

        assertThatThrownBy(() -> controller.listOwn(caller, null, 0, 10))
                .isInstanceOf(AccessDeniedException.class);
        verify(orderService, never()).listOwn(any(), any(), anyInt(), anyInt());
    }

    @Test
    void cancellingNeedsOrderCancel() {
        CurrentUser seller = authenticate(Set.of("SELLER"), "ROLE_SELLER", "ORDER.VIEW", "ORDER.LIST");
        CancelOrderRequest request = new CancelOrderRequest("Changed my mind");

        assertThatThrownBy(() -> controller.cancel(seller, UUID.randomUUID(), request))
                .isInstanceOf(AccessDeniedException.class);
        verify(orderService, never()).cancel(any(), any(), any());

        CurrentUser buyer = authenticate(Set.of("BUYER"), "ROLE_BUYER", "ORDER.CANCEL");
        UUID orderId = UUID.randomUUID();
        controller.cancel(buyer, orderId, request);
        verify(orderService).cancel(buyer, orderId, request);
    }

    @Test
    void buyerCannotAccessManagementOrSalesEvenWithOrderList() {
        CurrentUser buyer = authenticate(Set.of("BUYER"), "ROLE_BUYER", "ORDER.LIST");

        assertThatThrownBy(() -> controller.listManaged(buyer, null, null, null, null, null, null, 0, 10))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.listSales(buyer, null, null, null, 0, 10))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(orderService);
    }

    @Test
    void adminAndSupportWithOrderListReachManagement() {
        when(orderService.listManaged(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Page.empty(PageRequest.of(0, 10)));

        for (String role : Set.of("ADMIN", "SUPPORT_STAFF")) {
            CurrentUser manager = authenticate(Set.of(role), "ROLE_" + role, "ORDER.LIST");

            controller.listManaged(manager, null, null, null, null, null, null, 0, 10);

            verify(orderService).listManaged(manager, null, null, null, null, null, null, 0, 10);
        }
    }

    @Test
    void sellerCanReachSalesButCannotAccessManagement() {
        CurrentUser seller = authenticate(Set.of("SELLER"), "ROLE_SELLER", "ORDER.LIST");
        when(orderService.listSales(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Page.empty(PageRequest.of(0, 10)));

        controller.listSales(seller, null, null, null, 0, 10);

        verify(orderService).listSales(seller, null, null, null, 0, 10);
        assertThatThrownBy(() -> controller.listManaged(seller, null, null, null, null, null, null, 0, 10))
                .isInstanceOf(AccessDeniedException.class);
        verify(orderService, never()).listManaged(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void adminAndSupportWithoutSellerRoleCannotReadSales() {
        for (String role : Set.of("ADMIN", "SUPPORT_STAFF")) {
            CurrentUser manager = authenticate(Set.of(role), "ROLE_" + role, "ORDER.LIST");

            assertThatThrownBy(() -> controller.listSales(manager, null, null, null, 0, 10))
                    .isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(orderService);
    }

    @Test
    void managementAndSalesRolesStillNeedOrderList() {
        for (String role : Set.of("ADMIN", "SUPPORT_STAFF")) {
            CurrentUser manager = authenticate(Set.of(role), "ROLE_" + role, "ORDER.VIEW");
            assertThatThrownBy(() -> controller.listManaged(manager, null, null, null, null, null, null, 0, 10))
                    .isInstanceOf(AccessDeniedException.class);
        }
        CurrentUser seller = authenticate(Set.of("SELLER"), "ROLE_SELLER", "ORDER.VIEW");
        assertThatThrownBy(() -> controller.listSales(seller, null, null, null, 0, 10))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(orderService);
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
        OrderService orderService() {
            return Mockito.mock(OrderService.class);
        }

        @Bean
        OrderController orderController(OrderService orderService) {
            return new OrderController(orderService);
        }
    }
}
