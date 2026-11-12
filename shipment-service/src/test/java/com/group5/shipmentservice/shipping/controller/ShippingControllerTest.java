package com.group5.shipmentservice.shipping.controller;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.config.ShippingConfiguration;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shared.exception.ShipmentExceptionHandler;
import com.group5.shipmentservice.shared.exception.ShipmentSecurityProblemAdvice;
import com.group5.shipmentservice.shipping.adapter.CarrierRegistry;
import com.group5.shipmentservice.shipping.adapter.FakeCarrierAdapter;
import com.group5.shipmentservice.shipping.adapter.model.CarrierQuoteRequest;
import com.group5.shipmentservice.shipping.service.ShippingCatalogService;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import com.group5.shipmentservice.shipping.service.ShippingAddressService;
import com.group5.shipmentservice.shipping.service.ShippingQuoteService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
@ContextConfiguration(classes = ShippingControllerTest.TestApplication.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "eureka.client.enabled=false",
        "management.tracing.export.zipkin.enabled=false",
        "nexus.shipping.carrier=FAKE",
        "nexus.shipping.free-shipping-threshold=1000000",
        "nexus.shipping.default-warehouse-id=11111111-1111-4111-8111-111111111111",
        "nexus.shipping.pickups[11111111-1111-4111-8111-111111111111].province-code=1000001",
        "nexus.shipping.pickups[11111111-1111-4111-8111-111111111111].ward-code=1003646",
        "nexus.shipping.pickups[11111111-1111-4111-8111-111111111111].address-line=Địa chỉ lấy hàng kiểm thử",
        "nexus.security.public-endpoints[0].method=GET",
        "nexus.security.public-endpoints[0].path=/api/v1/shipping/provinces",
        "nexus.security.public-endpoints[1].method=GET",
        "nexus.security.public-endpoints[1].path=/api/v1/shipping/wards"
})
class ShippingControllerTest {
    private static final String PATH = "/api/v1/shipping";
    private static final String WAREHOUSE = "11111111-1111-4111-8111-111111111111";
    private static final String SKU = "22222222-2222-4222-8222-222222222222";

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private ShippingCatalogService catalog;

    @MockitoSpyBean
    private FakeCarrierAdapter fakeCarrier;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(catalog, fakeCarrier);
        when(catalog.getSubtotal(anyList(), any())).thenReturn(new BigDecimal("500000"));
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    void provincesArePublicAndUseStringCodesWithLogicalPage() throws Exception {
        mvc.perform(get(PATH + "/provinces"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[*].code", hasItem("1000001")))
                .andExpect(jsonPath("$.data[*].code", hasItem("1000002")))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.page.totalPages").value(1))
                .andExpect(jsonPath("$.token").doesNotExist());
        verifyNoInteractions(catalog);
    }

    @ParameterizedTest
    @CsvSource({"1000001,1003646", "1000002,1000003"})
    void wardsContainOnlyTheRequestedProvincesChildren(String provinceCode, String wardCode)
            throws Exception {
        mvc.perform(get(PATH + "/wards").param("provinceCode", provinceCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value(wardCode))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void missingProvinceCodeReturnsNamedBadRequest() throws Exception {
        mvc.perform(get(PATH + "/wards"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SHIPMENT_INVALID_PARAMETER"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("provinceCode"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void unknownProvinceCodeReturnsInvalidAddressInsteadOfAnEmptyWardList() throws Exception {
        mvc.perform(get(PATH + "/wards").param("provinceCode", "999999999"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("SHIPMENT_INVALID_ADDRESS"))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(fakeCarrier).getProvinces(any());
        verify(fakeCarrier, never()).getWards(any(), any());
        verifyNoInteractions(catalog);
    }

    @Test
    void legacyDistrictQueryCannotSubstituteProvinceCode() throws Exception {
        mvc.perform(get(PATH + "/wards").param("districtCode", "1444"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHIPMENT_INVALID_PARAMETER"))
                .andExpect(jsonPath("$.errors[0].field").value("provinceCode"));
        verifyNoInteractions(fakeCarrier, catalog);
    }

    @Test
    void districtsAreNotExposedByTheTwoLevelAddressApi() throws Exception {
        mvc.perform(get(PATH + "/districts").param("provinceCode", "1000001")
                        .header("Authorization", "Bearer buyer"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHIPMENT_NOT_FOUND"));
        verifyNoInteractions(fakeCarrier, catalog);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "abc", "0", "-1", "12.5", "01000001", "999999999999999999999999"})
    void invalidParentCodeIsRejectedBeforeCallingCarrier(String value) throws Exception {
        mvc.perform(get(PATH + "/wards").param("provinceCode", value))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(startsWith("SHIPMENT_")));
        verifyNoInteractions(fakeCarrier, catalog);
    }

    @Test
    void suppliedInvalidTokenIsRejectedOnPublicAddressPath() throws Exception {
        mvc.perform(get(PATH + "/provinces").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("SHIPMENT_UNAUTHENTICATED"));
        verifyNoInteractions(fakeCarrier);
    }

    @Test
    void quoteReturnsFeeAndDeliveryDateWithOneEnvelope() throws Exception {
        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(request(null)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data.length()").value(greaterThan(0)))
                .andExpect(jsonPath("$.data[0].carrier").value("FAKE"))
                .andExpect(jsonPath("$.data[0].serviceType").value("STANDARD"))
                .andExpect(jsonPath("$.data[0].fee").isNumber())
                .andExpect(jsonPath("$.data[0].fee").value(greaterThan(0)))
                .andExpect(jsonPath("$.data[0].estimatedDeliveryAt").isString())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(1))
                .andExpect(jsonPath("$.data.data").doesNotExist());
        verify(catalog).getSubtotal(anyList(), any());
        verify(fakeCarrier).quote(any(), any());
    }

    @Test
    void explicitConfiguredWarehouseIsAccepted() throws Exception {
        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(request(WAREHOUSE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].carrier").value("FAKE"));
    }

    @Test
    void quoteMapsPublicLine1ToTheCarrierAddress() throws Exception {
        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(request(null)))
                .andExpect(status().isOk());

        ArgumentCaptor<CarrierQuoteRequest> captured = ArgumentCaptor.forClass(CarrierQuoteRequest.class);
        verify(fakeCarrier).quote(captured.capture(), any());
        assertThat(captured.getValue().toAddress().addressLine()).isEqualTo("Địa chỉ kiểm thử");
    }

    @Test
    void addressLineCannotSubstituteTheDocumentedLine1Field() throws Exception {
        String body = request(null).replace("\"line1\":", "\"addressLine\":");

        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHIPMENT_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("toAddress.line1")));
        verifyNoInteractions(catalog, fakeCarrier);
    }

    @Test
    void omittedInsuranceDefaultsToZeroWithoutUsingCatalogSubtotal() throws Exception {
        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(request(null)))
                .andExpect(status().isOk());

        ArgumentCaptor<CarrierQuoteRequest> captured = ArgumentCaptor.forClass(CarrierQuoteRequest.class);
        verify(fakeCarrier).quote(captured.capture(), any());
        assertThat(captured.getValue().insuranceValue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void explicitInsuranceIsPassedUnchangedIndependentlyOfCatalogSubtotal() throws Exception {
        when(catalog.getSubtotal(anyList(), any())).thenReturn(new BigDecimal("4000000.75"));
        String body = requestWithInsurance("123456");

        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].fee").value(0));

        ArgumentCaptor<CarrierQuoteRequest> captured = ArgumentCaptor.forClass(CarrierQuoteRequest.class);
        verify(fakeCarrier).quote(captured.capture(), any());
        assertThat(captured.getValue().insuranceValue()).isEqualByComparingTo("123456");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "0.01", "123456.5", "5000001"})
    void invalidInsuranceReturnsBadRequestWithoutCallingDependencies(String insurance) throws Exception {
        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(requestWithInsurance(insurance)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SHIPMENT_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("insuranceValue")));
        verifyNoInteractions(catalog, fakeCarrier);
    }

    @Test
    void highSubtotalStillQualifiesForFreeShippingWithoutAutomaticInsurance() throws Exception {
        when(catalog.getSubtotal(anyList(), any())).thenReturn(new BigDecimal("9000000.25"));

        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(request(null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].fee").value(0));

        ArgumentCaptor<CarrierQuoteRequest> captured = ArgumentCaptor.forClass(CarrierQuoteRequest.class);
        verify(fakeCarrier).quote(captured.capture(), any());
        assertThat(captured.getValue().insuranceValue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1000000", "1000001"})
    void freeShippingRequiresARealAdapterQuoteAtAndAboveThreshold(String subtotal) throws Exception {
        when(catalog.getSubtotal(anyList(), any())).thenReturn(new BigDecimal(subtotal));

        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(request(null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].fee").value(0))
                .andExpect(jsonPath("$.data[0].estimatedDeliveryAt").isNotEmpty());
        verify(fakeCarrier).quote(any(), any());
    }

    @Test
    void belowThresholdKeepsCarrierFee() throws Exception {
        when(catalog.getSubtotal(anyList(), any())).thenReturn(new BigDecimal("999999.99"));

        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(request(null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].fee").value(greaterThan(0)));
    }

    @ParameterizedTest
    @CsvSource({"19999,STANDARD", "20000,HEAVY", "20001,HEAVY"})
    void fakeCarrierReturnsPublicServiceTypeAtTheWeightBoundary(int weight, String serviceType) throws Exception {
        String body = request(null).replace("\"weightGrams\":1200", "\"weightGrams\":" + weight);

        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].carrier").value("FAKE"))
                .andExpect(jsonPath("$.data[0].serviceType").value(serviceType))
                .andExpect(jsonPath("$.data[0].fee").value(30000));
    }

    @ParameterizedTest
    @CsvSource({"19999,STANDARD", "20000,HEAVY", "20001,HEAVY"})
    void freeShippingDoesNotChangeTheSelectedServiceType(int weight, String serviceType) throws Exception {
        when(catalog.getSubtotal(anyList(), any())).thenReturn(new BigDecimal("1000000"));
        String body = request(null).replace("\"weightGrams\":1200", "\"weightGrams\":" + weight);

        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].serviceType").value(serviceType))
                .andExpect(jsonPath("$.data[0].fee").value(0))
                .andExpect(jsonPath("$.data[0].estimatedDeliveryAt").isNotEmpty());
    }

    @Test
    void carrierFailureDoesNotBecomeFreeShipping() throws Exception {
        when(catalog.getSubtotal(anyList(), any())).thenReturn(new BigDecimal("1000000"));
        doThrow(new DomainException(ShipmentErrorCode.CARRIER_UNAVAILABLE))
                .when(fakeCarrier).quote(any(), any());

        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(request(null)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SHIPMENT_CARRIER_UNAVAILABLE"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void catalogFailureDoesNotBecomeAValidQuote() throws Exception {
        when(catalog.getSubtotal(anyList(), any())).thenThrow(new DomainException(ShipmentErrorCode.CATALOG_UNAVAILABLE));

        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(request(null)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SHIPMENT_CATALOG_UNAVAILABLE"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void unknownWarehouseReturnsNotFound() throws Exception {
        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("33333333-3333-4333-8333-333333333333")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHIPMENT_WAREHOUSE_NOT_FOUND"));
        verifyNoInteractions(catalog);
    }

    @Test
    void mismatchedAddressHierarchyIsRejected() throws Exception {
        String body = request(null).replace("\"provinceCode\":\"1000002\"", "\"provinceCode\":\"1000001\"");

        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHIPMENT_INVALID_ADDRESS"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"recipientName", "recipientPhone"})
    void quoteRequiresRecipientNameAndPhone(String field) throws Exception {
        String body = request(null).replace("\"" + field + "\":", "\"ignored" + field + "\":");

        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHIPMENT_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("toAddress." + field)));
        verifyNoInteractions(catalog, fakeCarrier);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "0123", "84901234567", "+840901234567", "090 123 4567"})
    void quoteRejectsInvalidVietnameseRecipientPhones(String phone) throws Exception {
        String body = request(null).replace("0901234567", phone);

        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHIPMENT_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("toAddress.recipientPhone")));
        verifyNoInteractions(catalog, fakeCarrier);
    }

    @Test
    void quoteAcceptsInternationalVietnameseRecipientPhone() throws Exception {
        String body = request(null).replace("0901234567", "+84901234567");

        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].carrier").value("FAKE"));
    }

    @Test
    void quoteRejectsLegacyOrNonCanonicalWardCodes() throws Exception {
        String body = request(null).replace("\"wardCode\":\"1000003\"", "\"wardCode\":\"01000003\"");

        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHIPMENT_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("toAddress.wardCode")));
        verifyNoInteractions(catalog, fakeCarrier);
    }

    @ParameterizedTest
    @ValueSource(strings = {"items", "parcel", "toAddress"})
    void requiredQuoteSectionsReturnAllNamedValidationErrors(String section) throws Exception {
        String body = request(null).replace("\"" + section + "\":", "\"ignored" + section + "\":");

        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(startsWith("SHIPMENT_")))
                .andExpect(jsonPath("$.errors[*].field", hasItem(section)));
        verifyNoInteractions(catalog, fakeCarrier);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void nonPositiveQuantityIsRejected(String quantity) throws Exception {
        String body = request(null).replace("\"quantity\":2", "\"quantity\":" + quantity);

        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(startsWith("SHIPMENT_")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("items[0].quantity")));
        verifyNoInteractions(catalog, fakeCarrier);
    }

    @Test
    void emptyItemsAreRejected() throws Exception {
        String body = request(null).replace("[{\"skuId\":\"" + SKU + "\",\"quantity\":2}]", "[]");

        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("items")));
        verifyNoInteractions(catalog, fakeCarrier);
    }

    @Test
    void malformedJsonReturnsShipmentProblem() throws Exception {
        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(startsWith("SHIPMENT_")));
        verifyNoInteractions(catalog, fakeCarrier);
    }

    @Test
    void quoteRequiresAuthentication() throws Exception {
        mvc.perform(post(PATH + "/quotes").contentType(MediaType.APPLICATION_JSON).content(request(null)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("SHIPMENT_UNAUTHENTICATED"));
        verifyNoInteractions(catalog, fakeCarrier);
    }

    @ParameterizedTest
    @ValueSource(strings = {"without-privilege", "admin"})
    void quoteRequiresShippingPrivilegeEvenForAdmin(String token) throws Exception {
        mvc.perform(post(PATH + "/quotes").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(request(null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SHIPMENT_FORBIDDEN"));
        verifyNoInteractions(catalog, fakeCarrier);
    }

    private String request(String warehouseId) {
        return """
                {
                  "toAddress":{"provinceCode":"1000002","wardCode":"1000003","line1":"Địa chỉ kiểm thử","recipientName":"Người nhận kiểm thử","recipientPhone":"0901234567"},
                  "items":[{"skuId":"%s","quantity":2}],
                  "parcel":{"weightGrams":1200,"lengthCm":30,"widthCm":20,"heightCm":15}%s
                }
                """.formatted(SKU, warehouseId == null ? "" : ",\"warehouseId\":\"" + warehouseId + "\"");
    }

    private String requestWithInsurance(String insuranceValue) {
        String body = request(null);
        int closingBrace = body.lastIndexOf('}');
        return body.substring(0, closingBrace) + ",\"insuranceValue\":" + insuranceValue + body.substring(closingBrace);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataJpaRepositoriesAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class
    }, excludeName = {"com.group5.common_outbox.autoconfigure.IdempotentConsumerAutoConfiguration",
            "com.group5.common_outbox.autoconfigure.OutboxAutoConfiguration"})
    @Import({ShippingConfiguration.class, CarrierRegistry.class, CarrierExecutor.class,
            ShippingAddressService.class, ShippingQuoteService.class, ShippingController.class,
            ShipmentSecurityProblemAdvice.class, ShipmentExceptionHandler.class})
    static class TestApplication {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                if (!List.of("buyer", "without-privilege", "admin").contains(token)) {
                    throw new BadJwtException("Token kiểm thử không hợp lệ");
                }
                Instant now = Instant.now();
                return Jwt.withTokenValue(token)
                        .header("alg", "RS256")
                        .subject("44444444-4444-4444-8444-444444444444")
                        .issuedAt(now.minusSeconds(30))
                        .expiresAt(now.plusSeconds(300))
                        .claim("roles", token.equals("admin") ? List.of("ADMIN") : List.of("BUYER"))
                        .claim("privileges", token.equals("buyer") ? List.of("SHIPPING.QUOTE") : List.of())
                        .build();
            };
        }
    }
}
