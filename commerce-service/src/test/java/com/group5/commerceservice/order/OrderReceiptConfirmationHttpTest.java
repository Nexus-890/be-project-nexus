package com.group5.commerceservice.order;

import com.group5.commerceservice.order.dto.response.OrderResponse;
import com.group5.commerceservice.order.service.OrderService;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(OrderReceiptConfirmationHttpTest.Config.class)
@WebAppConfiguration
class OrderReceiptConfirmationHttpTest {
    private static final UUID BUYER = UUID.randomUUID(), ID = UUID.randomUUID();
    @Autowired WebApplicationContext context;
    @Autowired OrderCompletionService completions;
    @Autowired OrderService orders;
    MockMvc mvc;

    @BeforeEach void setup() {
        reset(completions, orders);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        var delivered = OffsetDateTime.parse("2026-11-14T08:00:00Z");
        when(orders.getOrder(any(), eq(ID))).thenReturn(new OrderResponse(ID, "NX-RECEIPT-HTTP", BUYER,
                UUID.randomUUID(), OrderType.STANDARD, OrderStatus.COMPLETED, delivered.minusDays(3),
                BigDecimal.TEN, List.of(), null, null, UUID.randomUUID(), BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, delivered, delivered.plusDays(7), null, null));
    }

    @Test void buyerPostsNoBodyAndReceivesTheCompletedEnvelopeAndPersistedDates() throws Exception {
        mvc.perform(post("/api/v1/orders/{id}/confirm-receipt", ID).header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.orderId").value(ID.toString()))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.deliveredAt").value("2026-11-14T08:00:00Z"))
                .andExpect(jsonPath("$.data.autoCompleteAt").value("2026-11-21T08:00:00Z"));
        verify(completions).confirm(any(CurrentUser.class), eq(ID));
    }

    @Test void anonymousWrongRoleAndMissingPrivilegeFailBeforeService() throws Exception {
        mvc.perform(post("/api/v1/orders/{id}/confirm-receipt", ID)).andExpect(status().isUnauthorized());
        for (String token : List.of("no-privilege", "seller", "admin"))
            mvc.perform(post("/api/v1/orders/{id}/confirm-receipt", ID).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(completions, orders);
    }

    @Test void foreignOwnershipAndStaleStatusKeepNamedProblemDetails() throws Exception {
        for (var error : List.of(CommerceErrorCode.FORBIDDEN, CommerceErrorCode.ORDER_NOT_DELIVERED)) {
            doThrow(new DomainException(error)).when(completions).confirm(any(), eq(ID));
            mvc.perform(post("/api/v1/orders/{id}/confirm-receipt", ID).header("Authorization", "Bearer owner"))
                    .andExpect(status().is(error.status().value()))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value(error.code())).andExpect(jsonPath("$.data").doesNotExist());
        }
        verifyNoInteractions(orders);
    }

    @Test void reportingAReceiptIssueRequiresBuyerAndValidReason() throws Exception {
        mvc.perform(post("/api/v1/orders/{id}/receipt-issue", ID).header("Authorization", "Bearer owner")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Missing item\"}"))
                .andExpect(status().isOk());
        verify(completions).reportIssue(any(CurrentUser.class), eq(ID), eq("Missing item"));
        reset(completions, orders);
        for (String body : List.of("{}", "{\"reason\":\" \"}", "{\"reason\":\"" + "x".repeat(501) + "\"}"))
            mvc.perform(post("/api/v1/orders/{id}/receipt-issue", ID).header("Authorization", "Bearer owner")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/orders/{id}/receipt-issue", ID).header("Authorization", "Bearer seller")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Missing\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(completions, orders);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @ImportAutoConfiguration(SecurityAutoConfiguration.class)
    @Import(OrderReceiptConfirmationController.class)
    static class Config {
        @Bean OrderCompletionService completions() { return mock(OrderCompletionService.class); }
        @Bean OrderService orders() { return mock(OrderService.class); }
        @Bean GlobalExceptionHandler errors() { return new GlobalExceptionHandler(null); }
        @Bean JwtDecoder decoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "RS256").subject(BUYER.toString())
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                    .claim("roles", List.of(token.equals("seller") ? "SELLER" : token.equals("admin") ? "ADMIN" : "BUYER"))
                    .claim("privileges", token.equals("no-privilege") ? List.of() : List.of("ORDER.VIEW")).build();
        }
    }
}
