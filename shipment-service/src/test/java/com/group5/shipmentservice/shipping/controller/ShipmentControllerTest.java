package com.group5.shipmentservice.shipping.controller;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shared.exception.ShipmentExceptionHandler;
import com.group5.shipmentservice.shared.exception.ShipmentSecurityProblemAdvice;
import com.group5.shipmentservice.shipping.dto.request.CreateShipmentRequest;
import com.group5.shipmentservice.shipping.dto.response.ShipmentResponse;
import com.group5.shipmentservice.shipping.entity.Shipment;
import com.group5.shipmentservice.shipping.enums.ShipmentStatus;
import com.group5.shipmentservice.shipping.service.ShipmentCreationService;
import com.group5.shipmentservice.shipping.service.ShipmentReadService;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = ShipmentControllerTest.TestApplication.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "eureka.client.enabled=false",
        "management.tracing.export.zipkin.enabled=false"
})
class ShipmentControllerTest {
    private static final String PATH = "/api/v1/shipments";
    private static final UUID BUYER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ORDER = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SHIPMENT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID WAREHOUSE = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String CREATE_BODY = "{\"orderId\":\"" + ORDER + "\"}";

    @Autowired
    private WebApplicationContext context;
    @MockitoBean
    private ShipmentCreationService creation;
    @MockitoBean
    private ShipmentReadService reads;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(creation, reads);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    void manualCreateReturnsLocationAndSingleResourceEnvelope() throws Exception {
        Shipment shipment = mock(Shipment.class);
        when(shipment.getId()).thenReturn(SHIPMENT);
        when(shipment.getOrderId()).thenReturn(ORDER);
        when(shipment.getBuyerId()).thenReturn(BUYER);
        when(shipment.getWarehouseId()).thenReturn(WAREHOUSE);
        when(shipment.getCarrier()).thenReturn("FAKE");
        when(shipment.getTrackingCode()).thenReturn("FAKE-123");
        when(shipment.getShipmentStatus()).thenReturn(ShipmentStatus.CREATED);
        when(shipment.getShippingFee()).thenReturn(new BigDecimal("30000.00"));
        when(creation.createManually(eq("manual-202"), any())).thenReturn(shipment);

        mvc.perform(post(PATH).header("Authorization", "Bearer operator")
                        .header("Idempotency-Key", "manual-202")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", PATH + "/" + SHIPMENT))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data.id").value(SHIPMENT.toString()))
                .andExpect(jsonPath("$.data.orderId").value(ORDER.toString()))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.trackingCode").value("FAKE-123"))
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.page").doesNotExist());
        verify(creation).createManually("manual-202", new CreateShipmentRequest(ORDER, null, null, null));
    }

    @Test
    void detailReturnsShipmentSnapshotWithEnvelope() throws Exception {
        when(reads.get(any(CurrentUser.class), eq(SHIPMENT))).thenReturn(response());

        mvc.perform(get(PATH + "/" + SHIPMENT).header("Authorization", "Bearer buyer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data.buyerId").value(BUYER.toString()))
                .andExpect(jsonPath("$.data.shippingInfo.recipientName").value("Nguyen Van A"))
                .andExpect(jsonPath("$.data.trackingCode").isEmpty())
                .andExpect(jsonPath("$.data.status").value("PENDING_TRACKING"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.page").doesNotExist());
    }

    @Test
    void listReturnsArrayAndSiblingPageIncludingRequestedFilters() throws Exception {
        when(reads.list(any(CurrentUser.class), eq(ORDER), eq(ShipmentStatus.PENDING_TRACKING), eq(1), eq(5)))
                .thenReturn(new PageImpl<>(List.of(response()), PageRequest.of(1, 5), 6));

        mvc.perform(get(PATH).header("Authorization", "Bearer buyer")
                        .param("orderId", ORDER.toString()).param("status", "PENDING_TRACKING")
                        .param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(SHIPMENT.toString()))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.size").value(5))
                .andExpect(jsonPath("$.page.totalElements").value(6))
                .andExpect(jsonPath("$.page.totalPages").value(2));
    }

    @Test
    void emptyListPreservesDefaultsAndPageMetadata() throws Exception {
        when(reads.list(any(CurrentUser.class), isNull(), isNull(), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mvc.perform(get(PATH).header("Authorization", "Bearer buyer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
    }

    @Test
    void hiddenShipmentUsesNamedNotFoundProblem() throws Exception {
        when(reads.get(any(CurrentUser.class), eq(SHIPMENT)))
                .thenThrow(new DomainException(ShipmentErrorCode.NOT_FOUND));

        mvc.perform(get(PATH + "/" + SHIPMENT).header("Authorization", "Bearer buyer"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SHIPMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value(PATH + "/" + SHIPMENT))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void missingIdempotencyHeaderHasNamedProblem() throws Exception {
        mvc.perform(post(PATH).header("Authorization", "Bearer operator")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SHIPMENT_IDEMPOTENCY_KEY_REQUIRED"));
        verifyNoInteractions(creation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankIdempotencyHeaderIsRejected(String key) throws Exception {
        mvc.perform(post(PATH).header("Authorization", "Bearer operator")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHIPMENT_IDEMPOTENCY_KEY_REQUIRED"));
        verifyNoInteractions(creation);
    }

    @Test
    void oversizedIdempotencyHeaderIsRejected() throws Exception {
        mvc.perform(post(PATH).header("Authorization", "Bearer operator")
                        .header("Idempotency-Key", "x".repeat(101))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHIPMENT_INVALID_PARAMETER"));
        verifyNoInteractions(creation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"orderId\":null}",
            "{\"orderId\":\"22222222-2222-4222-8222-222222222222\",\"carrier\":\" \"}",
            "{\"orderId\":\"22222222-2222-4222-8222-222222222222\",\"serviceType\":\"\"}"})
    void invalidCreateBodyReturnsFieldValidationProblem(String body) throws Exception {
        mvc.perform(post(PATH).header("Authorization", "Bearer operator")
                        .header("Idempotency-Key", "manual-202")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SHIPMENT_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors").isNotEmpty());
        verifyNoInteractions(creation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/invalid-uuid", "?orderId=invalid-uuid", "?status=UNKNOWN"})
    void malformedPathOrFiltersUseShipmentParameterProblem(String suffix) throws Exception {
        mvc.perform(get(PATH + suffix).header("Authorization", "Bearer buyer"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHIPMENT_INVALID_PARAMETER"));
        verifyNoInteractions(reads);
    }

    @Test
    void missingAuthenticationRejectsReadsAndManualCreate() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("SHIPMENT_UNAUTHENTICATED"));
        mvc.perform(post(PATH).header("Idempotency-Key", "manual-202")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SHIPMENT_UNAUTHENTICATED"));
        verifyNoInteractions(reads, creation);
    }

    @Test
    void adminRoleWithoutPrivilegeCannotReadOrCreate() throws Exception {
        mvc.perform(get(PATH).header("Authorization", "Bearer admin-no-privilege"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SHIPMENT_FORBIDDEN"));
        mvc.perform(post(PATH).header("Authorization", "Bearer admin-no-privilege")
                        .header("Idempotency-Key", "manual-202")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SHIPMENT_FORBIDDEN"));
        verifyNoInteractions(reads, creation);
    }

    @Test
    void buyerViewPrivilegeDoesNotAllowManualCreation() throws Exception {
        mvc.perform(post(PATH).header("Authorization", "Bearer buyer")
                        .header("Idempotency-Key", "manual-202")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SHIPMENT_FORBIDDEN"));
        verifyNoInteractions(creation);
    }

    @Test
    void buyerCannotUseManualCreationToReadAnotherOrderEvenWithCreatePrivilege() throws Exception {
        mvc.perform(post(PATH).header("Authorization", "Bearer buyer-with-create")
                        .header("Idempotency-Key", "manual-202")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SHIPMENT_FORBIDDEN"));
        verifyNoInteractions(creation);
    }

    private static ShipmentResponse response() {
        OffsetDateTime created = OffsetDateTime.parse("2026-11-11T00:00:00Z");
        return new ShipmentResponse(SHIPMENT, ORDER, WAREHOUSE, BUYER, "FAKE", "STANDARD", null,
                ShipmentStatus.PENDING_TRACKING, new BigDecimal("30000.00"),
                Map.of("recipientName", "Nguyen Van A"), created, created, 0);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataJpaRepositoriesAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class
    }, excludeName = {"com.group5.common_outbox.autoconfigure.IdempotentConsumerAutoConfiguration",
            "com.group5.common_outbox.autoconfigure.OutboxAutoConfiguration"})
    @Import({ShipmentController.class, ShipmentSecurityProblemAdvice.class, ShipmentExceptionHandler.class})
    static class TestApplication {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                if (!List.of("buyer", "buyer-with-create", "operator", "admin-no-privilege").contains(token)) {
                    throw new BadJwtException("Invalid test token");
                }
                return Jwt.withTokenValue(token)
                        .header("alg", "RS256")
                        .subject(BUYER.toString())
                        .issuedAt(Instant.parse("2026-11-11T00:00:00Z"))
                        .expiresAt(Instant.parse("2026-11-12T00:00:00Z"))
                        .claim("roles", token.equals("operator") ? List.of("SUPPORT_STAFF")
                                : token.equals("admin-no-privilege") ? List.of("ADMIN") : List.of("BUYER"))
                        .claim("privileges", List.of("operator", "buyer-with-create").contains(token)
                                ? List.of("SHIPPING.VIEW_SHIPMENT", "SHIPPING.CREATE_SHIPMENT")
                                : token.equals("buyer") ? List.of("SHIPPING.VIEW_SHIPMENT") : List.of())
                        .build();
            };
        }
    }
}
