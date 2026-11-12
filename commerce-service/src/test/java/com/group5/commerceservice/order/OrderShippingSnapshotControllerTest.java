package com.group5.commerceservice.order;

import com.group5.common_core.exception.DomainException;
import com.group5.commerceservice.order.controller.OrderShippingSnapshotController;
import com.group5.commerceservice.order.dto.response.OrderShippingSnapshotResponse;
import com.group5.commerceservice.order.service.OrderShippingSnapshotService;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = OrderShippingSnapshotControllerTest.TestApplication.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "eureka.client.enabled=false",
        "management.tracing.export.zipkin.enabled=false"
})
class OrderShippingSnapshotControllerTest {
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID BUYER = UUID.randomUUID();
    private static final String PATH = "/internal/v1/orders/" + ORDER + "/shipping-snapshot";
    @Autowired WebApplicationContext context;
    @MockitoBean OrderShippingSnapshotService snapshots;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(snapshots);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "support"})
    void authorizedStaffReceiveTheCommerceOwnedSnapshotInAnEnvelope(String token) throws Exception {
        when(snapshots.get(ORDER)).thenReturn(new OrderShippingSnapshotResponse(ORDER, BUYER, 4, "PAID",
                OffsetDateTime.parse("2026-11-11T03:00:00Z"), null, new BigDecimal("15000.00"),
                Map.of("recipient", Map.of("name", "Buyer"))));

        mvc.perform(get(PATH).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data.orderId").value(ORDER.toString()))
                .andExpect(jsonPath("$.data.buyerId").value(BUYER.toString()))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.shippingFee").value(15000.00))
                .andExpect(jsonPath("$.page").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"buyer", "seller", "admin-no-privilege"})
    void aRoleAndTheCreationPrivilegeAreBothRequired(String token) throws Exception {
        mvc.perform(get(PATH).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(snapshots);
    }

    @Test
    void missingAndInvalidBearerCannotReadSnapshot() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(snapshots);
    }

    @Test
    void unknownOrderReturnsNamedProblemDetails() throws Exception {
        when(snapshots.get(ORDER)).thenThrow(new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));

        mvc.perform(get(PATH).header("Authorization", "Bearer support"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COMMERCE_ORDER_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORDER_NOT_PAID", "INVALID_SHIPPING_SNAPSHOT"})
    void invalidPaidStateAndShippingDataRemainNamedConflicts(String error) throws Exception {
        when(snapshots.get(ORDER)).thenThrow(new DomainException(CommerceErrorCode.valueOf(error)));

        mvc.perform(get(PATH).header("Authorization", "Bearer admin"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COMMERCE_" + error));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class
    }, excludeName = {"com.group5.common_outbox.autoconfigure.IdempotentConsumerAutoConfiguration",
            "com.group5.common_outbox.autoconfigure.OutboxAutoConfiguration"})
    @Import(OrderShippingSnapshotController.class)
    static class TestApplication {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                if (!List.of("admin", "support", "buyer", "seller", "admin-no-privilege").contains(token)) {
                    throw new BadJwtException("Invalid test token");
                }
                String role = switch (token) {
                    case "support" -> "SUPPORT_STAFF";
                    case "buyer" -> "BUYER";
                    case "seller" -> "SELLER";
                    default -> "ADMIN";
                };
                return Jwt.withTokenValue(token).header("alg", "RS256").subject(BUYER.toString())
                        .issuedAt(Instant.now().minusSeconds(60)).expiresAt(Instant.now().plusSeconds(600))
                        .claim("roles", List.of(role)).claim("privileges", token.equals("admin-no-privilege")
                                ? List.of("ORDER.VIEW") : List.of("SHIPPING.CREATE_SHIPMENT"))
                        .build();
            };
        }
    }
}
