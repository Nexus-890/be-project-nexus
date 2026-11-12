package com.group5.commerceservice.order;

import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.entity.OrderGroup;
import com.group5.commerceservice.order.entity.OrderItem;
import com.group5.commerceservice.order.entity.OrderStatusHistory;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.repository.OrderStatusHistoryRepository;
import com.group5.common_security.autoconfigure.SecurityAutoConfiguration;
import com.group5.common_web.exception.GlobalExceptionHandler;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(OrderReceiptHttpTest.Config.class)
@WebAppConfiguration
class OrderReceiptHttpTest {
    private static final UUID BUYER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID ID = UUID.randomUUID();
    private static final OffsetDateTime COMPLETED_AT = OffsetDateTime.parse("2026-11-07T08:15:00Z");
    @Autowired WebApplicationContext context;
    @Autowired OrderRepository orders;
    @Autowired OrderStatusHistoryRepository history;
    private MockMvc mvc;
    private Order order;

    @BeforeEach void setup() {
        reset(orders, history);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        order = new Order();
        order.setId(ID);
        order.setUserId(BUYER);
        order.setSellerId(UUID.randomUUID());
        order.setOrderNumber("NX-RECEIPT-204");
        order.setStatus(OrderStatus.COMPLETED);
        order.setSubtotal(new BigDecimal("300000.00"));
        order.setDiscountAmount(new BigDecimal("10000.00"));
        order.setTaxAmount(new BigDecimal("2000.00"));
        order.setShippingFee(new BigDecimal("15000.00"));
        order.setTotalAmount(new BigDecimal("307000.00"));
        OrderGroup group = new OrderGroup();
        group.setCurrency("VND");
        order.setOrderGroup(group);
        order.setShippingInfo(Map.of("shippingAddress", Map.of("recipientName", "Trình & Khang",
                "addressLine", "<img src=x onerror=alert(1)>", "city", "Hồ Chí Minh")));
        OrderItem item = new OrderItem();
        item.setProductName("Phone <script>alert('x')</script>");
        item.setSkuCode("SKU-204");
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("150000.00"));
        item.setTotalPrice(new BigDecimal("300000.00"));
        order.getItems().add(item);
        OrderStatusHistory completion = new OrderStatusHistory();
        completion.setNewStatus(OrderStatus.COMPLETED);
        completion.setCreatedAt(COMPLETED_AT);
        when(orders.findById(ID)).thenReturn(Optional.of(order));
        when(history.findByOrderIdOrderByCreatedAtAscIdAsc(ID)).thenReturn(List.of(completion));
    }

    @Test void ownerReceivesUtf8HtmlFromSnapshotsWithStableCompletionDate() throws Exception {
        String html = mvc.perform(get("/api/v1/orders/{id}/invoice", ID)
                        .header("Authorization", "Bearer owner").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk()).andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).startsWith("<!doctype html>").contains("NX-RECEIPT-204", ID.toString(),
                "Trình &amp; Khang", "Hồ Chí Minh", "SKU-204", "150000.00", "300000.00", "10000.00",
                "2000.00", "15000.00", "307000.00", "Currency: VND", "2026-11-07T08:15:00Z")
                .contains("&lt;script&gt;", "&lt;img").doesNotContain("<script>", "<img", "\"data\"");
        assertThat(mvc.perform(get("/api/v1/orders/{id}/invoice", ID).header("Authorization", "Bearer owner"))
                .andReturn().getResponse().getContentAsString()).isEqualTo(html);
    }

    @Test void everyIncompleteStatusReturnsNamedConflictEvenWhenHtmlIsRequested() throws Exception {
        for (OrderStatus status : OrderStatus.values()) {
            if (status == OrderStatus.COMPLETED) continue;
            order.setStatus(status);
            mvc.perform(get("/api/v1/orders/{id}/invoice", ID).header("Authorization", "Bearer owner")
                            .accept(MediaType.TEXT_HTML))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("COMMERCE_ORDER_NOT_COMPLETED"))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
        verifyNoInteractions(history);
    }

    @Test void anotherUserAndAdminCannotReadTheReceiptOrLearnIncompleteStatus() throws Exception {
        for (String token : List.of("other", "admin")) {
            for (OrderStatus status : List.of(OrderStatus.COMPLETED, OrderStatus.PENDING)) {
                order.setStatus(status);
                mvc.perform(get("/api/v1/orders/{id}/invoice", ID).header("Authorization", "Bearer " + token)
                                .accept(MediaType.TEXT_HTML))
                        .andExpect(status().isForbidden())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.code").value("COMMERCE_FORBIDDEN"));
            }
        }
        verifyNoInteractions(history);
    }

    @Test void missingOrderReturnsNamedNotFound() throws Exception {
        mvc.perform(get("/api/v1/orders/{id}/invoice", UUID.randomUUID()).header("Authorization", "Bearer owner"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COMMERCE_ORDER_NOT_FOUND"));
    }

    @Test void authenticationAndOrderViewAreRequiredBeforeAnyDatabaseRead() throws Exception {
        mvc.perform(get("/api/v1/orders/{id}/invoice", ID)).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/api/v1/orders/{id}/invoice", ID).header("Authorization", "Bearer no-privilege"))
                .andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(orders, history);
    }

    @Test void malformedIdRemainsProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/orders/not-a-uuid/invoice").header("Authorization", "Bearer owner"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        verifyNoInteractions(orders, history);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @ImportAutoConfiguration(SecurityAutoConfiguration.class)
    @Import({OrderReceiptController.class, OrderReceiptService.class, OrderReceiptRenderer.class})
    static class Config {
        @Bean OrderRepository orders() { return mock(OrderRepository.class); }
        @Bean OrderStatusHistoryRepository history() { return mock(OrderStatusHistoryRepository.class); }
        @Bean GlobalExceptionHandler errors() { return new GlobalExceptionHandler(null); }
        @Bean JwtDecoder decoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "RS256")
                    .subject((token.equals("other") || token.equals("admin") ? OTHER : BUYER).toString())
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                    .claim("roles", List.of(token.equals("admin") ? "ADMIN" : "BUYER"))
                    .claim("privileges", token.equals("no-privilege") ? List.of() : List.of("ORDER.VIEW")).build();
        }
    }
}
