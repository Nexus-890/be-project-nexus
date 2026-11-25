package com.group5.shipmentservice.shipping.controller;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shared.exception.ShipmentExceptionHandler;
import com.group5.shipmentservice.shared.exception.ShipmentSecurityProblemAdvice;
import com.group5.shipmentservice.shipping.dto.request.PickupAddressRequest;
import com.group5.shipmentservice.shipping.dto.response.PickupAddressCheckResponse;
import com.group5.shipmentservice.shipping.service.PickupAddressCheckService;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = InternalPickupAddressControllerTest.TestApplication.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "eureka.client.enabled=false",
        "management.tracing.export.zipkin.enabled=false"
})
class InternalPickupAddressControllerTest {
    private static final String PATH = "/internal/v1/shipping/pickup-address-checks";
    private static final String BODY = "{\"provinceCode\":\"1000001\",\"wardCode\":\"1003504\",\"line1\":\"12 Le Loi\"}";

    @Autowired private WebApplicationContext context;
    @MockitoBean private PickupAddressCheckService checks;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(checks);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void aSellerGetsTheCanonicalPickupArea() throws Exception {
        when(checks.check(any())).thenReturn(new PickupAddressCheckResponse("GHN", "1000001", "Hồ Chí Minh",
                "1003504", "Phường Bến Thành", "12 Le Loi"));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer seller"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wardName").value("Phường Bến Thành"))
                .andExpect(jsonPath("$.data.carrier").value("GHN"));
        verify(checks).check(new PickupAddressRequest("1000001", "1003504", "12 Le Loi"));
    }

    @Test
    void aConflictKeepsItsCode() throws Exception {
        when(checks.check(any())).thenThrow(new DomainException(ShipmentErrorCode.PICKUP_ADDRESS_CONFLICT));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer seller"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHIPMENT_PICKUP_ADDRESS_CONFLICT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"buyer", "seller-without-quote"})
    void onlySellersWithTheQuotePrivilegeMayCheck(String token) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(checks);
    }

    @Test
    void anIncompleteAddressIsRejectedBeforeTheCarrier() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provinceCode\":\"1000001\",\"wardCode\":\"1003504\",\"line1\":\" \"}")
                        .header("Authorization", "Bearer seller"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(checks);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataJpaRepositoriesAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class
    }, excludeName = {"com.group5.common_outbox.autoconfigure.IdempotentConsumerAutoConfiguration",
            "com.group5.common_outbox.autoconfigure.OutboxAutoConfiguration"})
    @Import({InternalPickupAddressController.class, ShipmentSecurityProblemAdvice.class, ShipmentExceptionHandler.class})
    static class TestApplication {
        private static final Map<String, List<String>> ROLES = Map.of("seller", List.of("SELLER", "BUYER"),
                "buyer", List.of("BUYER"), "seller-without-quote", List.of("SELLER"));

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                if (!ROLES.containsKey(token)) throw new BadJwtException("Token kiểm thử không hợp lệ");
                return Jwt.withTokenValue(token).header("alg", "RS256")
                        .subject("11111111-1111-4111-8111-111111111111")
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                        .claim("roles", ROLES.get(token))
                        .claim("privileges", token.equals("seller-without-quote") ? List.of()
                                : List.of("SHIPPING.QUOTE"))
                        .build();
            };
        }
    }
}
