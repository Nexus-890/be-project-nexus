package com.group5.shipmentservice.shipping.service;

import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.shipmentservice.shared.config.ShippingProperties;
import com.group5.shipmentservice.shipping.client.CommerceOrderClient;
import com.group5.shipmentservice.shipping.client.config.CommerceDeadlineOptions;
import com.group5.shipmentservice.shipping.dto.response.PaidOrderShippingResponse;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.RetryableException;
import feign.codec.DecodeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaidOrderSnapshotServiceTest {
    private static final UUID ORDER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BUYER = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID WAREHOUSE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID SKU = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private CommerceOrderClient commerce;
    private PaidOrderSnapshotService service;
    private CarrierExecutor executor;

    @BeforeEach
    void setUp() {
        commerce = mock(CommerceOrderClient.class);
        var properties = mock(ShippingProperties.class);
        when(properties.apiTimeoutMs()).thenReturn(3000);
        when(properties.breakerFailureThreshold()).thenReturn(5);
        when(properties.breakerOpenMs()).thenReturn(30000);
        executor = new CarrierExecutor(properties);
        service = new PaidOrderSnapshotService(commerce, executor, JsonMapper.shared());
        authenticate("staff-access-A");
        when(commerce.getPaidOrder(eq(ORDER), any(), any())).thenReturn(ApiResponse.of(valid()));
    }

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void loadsTrustedBuyerPriceAndItemsAndRelaysTheCurrentStaffAccessToken() {
        var result = service.load(ORDER);
        assertThat(result.orderId()).isEqualTo(ORDER);
        assertThat(result.buyerId()).isEqualTo(BUYER);
        assertThat(result.warehouseId()).isEqualTo(WAREHOUSE);
        assertThat(result.shippingFee()).isEqualByComparingTo("30000.00");
        assertThat(result.shippingInfo()).isEqualTo(valid().shippingInfo());
        verify(commerce).getPaidOrder(eq(ORDER), eq("Bearer staff-access-A"), any(CommerceDeadlineOptions.class));

        authenticate("staff-access-B");
        service.load(ORDER);
        verify(commerce).getPaidOrder(eq(ORDER), eq("Bearer staff-access-B"), any(CommerceDeadlineOptions.class));
    }

    @Test
    void rejectsMissingOrUnusableAuthenticationBeforeCallingCommerce() {
        SecurityContextHolder.clearContext();
        assertCode(() -> service.load(ORDER), "SHIPMENT_UNAUTHENTICATED");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("unrelated-principal", "unused"));
        assertCode(() -> service.load(ORDER), "SHIPMENT_UNAUTHENTICATED");
        var unauthenticated = token("staff-access-A");
        unauthenticated.setAuthenticated(false);
        SecurityContextHolder.getContext().setAuthentication(unauthenticated);
        assertCode(() -> service.load(ORDER), "SHIPMENT_UNAUTHENTICATED");
        verifyNoInteractions(commerce);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 409, 422, 500, 502, 503, 504})
    void translatesCommerceStatusToNamedShipmentProblemWithoutRetry(int status) {
        when(commerce.getPaidOrder(eq(ORDER), any(), any())).thenThrow(feignFailure(status, "COMMERCE_ORDER_ERROR"));
        String expected = switch (status) {
            case 401 -> "SHIPMENT_UNAUTHENTICATED";
            case 403 -> "SHIPMENT_FORBIDDEN";
            case 404 -> "SHIPMENT_ORDER_NOT_FOUND";
            case 409 -> "SHIPMENT_ORDER_NOT_PAID";
            case 422 -> "SHIPMENT_INVALID_ORDER_SNAPSHOT";
            default -> "SHIPMENT_COMMERCE_UNAVAILABLE";
        };
        assertCode(() -> service.load(ORDER), expected);
        verify(commerce).getPaidOrder(eq(ORDER), any(), any());
    }

    @Test
    void distinguishesAnInvalidPersistedSnapshotFromAnUnpaidOrder() {
        when(commerce.getPaidOrder(eq(ORDER), any(), any()))
                .thenThrow(feignFailure(409, "COMMERCE_INVALID_SHIPPING_SNAPSHOT"));
        assertCode(() -> service.load(ORDER), "SHIPMENT_INVALID_ORDER_SNAPSHOT");
    }

    @Test
    void malformedSuccessEnvelopeBecomesInvalidSnapshot() {
        when(commerce.getPaidOrder(eq(ORDER), any(), any()))
                .thenThrow(new DecodeException(200, "malformed fixture", request()));
        assertCode(() -> service.load(ORDER), "SHIPMENT_INVALID_ORDER_SNAPSHOT");
    }

    @Test
    void timeoutBecomesCommerceUnavailableWithoutRetry() {
        when(commerce.getPaidOrder(eq(ORDER), any(), any())).thenThrow(new RetryableException(
                -1, "fixture timeout", Request.HttpMethod.GET, (Long) null, request()));
        assertCode(() -> service.load(ORDER), "SHIPMENT_COMMERCE_UNAVAILABLE");
        verify(commerce).getPaidOrder(eq(ORDER), any(), any());
    }

    @Test
    void nullEnvelopeOrNullDataDoesNotBecomeAPaidSnapshot() {
        when(commerce.getPaidOrder(eq(ORDER), any(), any())).thenReturn(null);
        assertCode(() -> service.load(ORDER), "SHIPMENT_INVALID_ORDER_SNAPSHOT");
        when(commerce.getPaidOrder(eq(ORDER), any(), any())).thenReturn(ApiResponse.of(null));
        assertCode(() -> service.load(ORDER), "SHIPMENT_INVALID_ORDER_SNAPSHOT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "CANCELED", "SHIPPED", "REFUNDED"})
    void onlyPaidOrdersMaySupplyANewShipment(String state) {
        PaidOrderShippingResponse good = valid();
        when(commerce.getPaidOrder(eq(ORDER), any(), any())).thenReturn(ApiResponse.of(new PaidOrderShippingResponse(
                good.orderId(), good.buyerId(), good.orderVersion(), state, good.paidAt(), good.warehouseId(),
                good.shippingFee(), good.shippingInfo())));
        assertCode(() -> service.load(ORDER), "SHIPMENT_ORDER_NOT_PAID");
    }

    @ParameterizedTest
    @MethodSource("invalidMetadata")
    void rejectsInconsistentOrMissingTrustedMetadata(PaidOrderShippingResponse response) {
        when(commerce.getPaidOrder(eq(ORDER), any(), any())).thenReturn(ApiResponse.of(response));
        assertCode(() -> service.load(ORDER), "SHIPMENT_INVALID_ORDER_SNAPSHOT");
    }

    static Stream<PaidOrderShippingResponse> invalidMetadata() {
        var good = valid();
        return Stream.of(
                copy(UUID.randomUUID(), good.buyerId(), 1, good.paidAt(), good.shippingFee(), good.shippingInfo()),
                copy(null, good.buyerId(), 1, good.paidAt(), good.shippingFee(), good.shippingInfo()),
                copy(ORDER, null, 1, good.paidAt(), good.shippingFee(), good.shippingInfo()),
                copy(ORDER, BUYER, -1, good.paidAt(), good.shippingFee(), good.shippingInfo()),
                copy(ORDER, BUYER, 1, null, good.shippingFee(), good.shippingInfo()),
                copy(ORDER, BUYER, 1, good.paidAt(), null, good.shippingInfo()),
                copy(ORDER, BUYER, 1, good.paidAt(), new BigDecimal("-1.00"), good.shippingInfo()),
                copy(ORDER, BUYER, 1, good.paidAt(), new BigDecimal("1.001"), good.shippingInfo()),
                copy(ORDER, BUYER, 1, good.paidAt(), new BigDecimal("100000000000000000.00"), good.shippingInfo()),
                copy(ORDER, BUYER, 1, good.paidAt(), good.shippingFee(), null));
    }

    @ParameterizedTest
    @MethodSource("invalidShippingInfo")
    void incompleteRecipientOrInvalidOrderItemsCannotBecomeAShipment(Map<String, Object> info) {
        PaidOrderShippingResponse good = valid();
        when(commerce.getPaidOrder(eq(ORDER), any(), any())).thenReturn(ApiResponse.of(copy(
                ORDER, BUYER, 1, good.paidAt(), good.shippingFee(), info)));
        assertCode(() -> service.load(ORDER), "SHIPMENT_INVALID_ORDER_SNAPSHOT");
    }

    static Stream<Map<String, Object>> invalidShippingInfo() {
        Map<String, Object> missingRecipient = new LinkedHashMap<>(shippingInfo());
        missingRecipient.remove("recipient");
        Map<String, Object> missingName = new LinkedHashMap<>(shippingInfo());
        missingName.put("recipient", Map.of("phone", "0900000000", "address", Map.of("line1", "Test address")));
        Map<String, Object> missingPhone = new LinkedHashMap<>(shippingInfo());
        missingPhone.put("recipient", Map.of("name", "Buyer", "address", Map.of("line1", "Test address")));
        Map<String, Object> missingAddress = new LinkedHashMap<>(shippingInfo());
        missingAddress.put("recipient", Map.of("name", "Buyer", "phone", "0900000000"));
        Map<String, Object> missingItems = new LinkedHashMap<>(shippingInfo());
        missingItems.remove("items");
        Map<String, Object> emptyItems = new LinkedHashMap<>(shippingInfo());
        emptyItems.put("items", List.of());
        Map<String, Object> invalidSku = withItem(Map.of("skuId", "invalid-id", "productName", "Item", "quantity", 1));
        Map<String, Object> missingProductName = withItem(Map.of("skuId", SKU.toString(), "quantity", 1));
        Map<String, Object> emptyProductName = withItem(Map.of("skuId", SKU.toString(), "productName", " ", "quantity", 1));
        Map<String, Object> zeroQuantity = withItem(Map.of("skuId", SKU.toString(), "productName", "Item", "quantity", 0));
        Map<String, Object> fractionalQuantity = withItem(Map.of("skuId", SKU.toString(), "productName", "Item", "quantity", 1.5));
        Map<String, Object> overflowingQuantity = withItem(Map.of("skuId", SKU.toString(), "productName", "Item", "quantity", Long.MAX_VALUE));
        return Stream.of(missingRecipient, missingName, missingPhone, missingAddress, missingItems, emptyItems,
                invalidSku, missingProductName, emptyProductName, zeroQuantity, fractionalQuantity, overflowingQuantity);
    }

    @Test
    void legacyRecipientWithoutLocalityCodesIsAcceptedAsUnpreparedRatherThanFabricated() {
        var result = service.load(ORDER);
        assertThat(result.isPrepared()).isFalse();
        assertThat(result.shippingInfo()).isEqualTo(shippingInfo());
        Map<?, ?> address = (Map<?, ?>) ((Map<?, ?>) result.shippingInfo().get("recipient")).get("address");
        assertThat(address.containsKey("provinceCode")).isFalse();
        assertThat(address.containsKey("wardCode")).isFalse();
    }

    @Test
    void fiveAvailabilityFailuresOpenTheCommerceBreakerAndStopFurtherCalls() {
        when(commerce.getPaidOrder(eq(ORDER), any(), any())).thenThrow(feignFailure(503, "COMMERCE_UNAVAILABLE"));
        for (int i = 0; i < 5; i++) assertCode(() -> service.load(ORDER), "SHIPMENT_COMMERCE_UNAVAILABLE");
        verify(commerce, times(5)).getPaidOrder(eq(ORDER), any(), any());
        assertThat(executor.isAvailable("COMMERCE")).isFalse();
        clearInvocations(commerce);
        assertCode(() -> service.load(ORDER), "SHIPMENT_COMMERCE_UNAVAILABLE");
        verifyNoInteractions(commerce);
    }

    @Test
    void businessRejectionsDoNotOpenTheCommerceAvailabilityBreaker() {
        when(commerce.getPaidOrder(eq(ORDER), any(), any())).thenThrow(feignFailure(404, "COMMERCE_ORDER_NOT_FOUND"));
        for (int i = 0; i < 6; i++) assertCode(() -> service.load(ORDER), "SHIPMENT_ORDER_NOT_FOUND");
        assertThat(executor.isAvailable("COMMERCE")).isTrue();
        verify(commerce, times(6)).getPaidOrder(eq(ORDER), any(), any());
    }

    private static Map<String, Object> shippingInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("recipient", Map.of("name", "Buyer", "phone", "0900000000", "address", Map.of("line1", "Test address")));
        info.put("items", List.of(Map.of("skuId", SKU.toString(), "productName", "Stored Item", "quantity", 2)));
        info.put("parcel", null);
        info.put("pickupAddress", null);
        return info;
    }

    private static Map<String, Object> withItem(Map<String, Object> item) {
        Map<String, Object> info = new LinkedHashMap<>(shippingInfo());
        info.put("items", List.of(item));
        return info;
    }

    private static PaidOrderShippingResponse valid() {
        return copy(ORDER, BUYER, 1, OffsetDateTime.parse("2026-11-11T00:00:00Z"), new BigDecimal("30000.00"), shippingInfo());
    }

    private static PaidOrderShippingResponse copy(UUID orderId, UUID buyerId, long version,
            OffsetDateTime paidAt, BigDecimal fee, Map<String, Object> info) {
        return new PaidOrderShippingResponse(orderId, buyerId, version, "PAID", paidAt, WAREHOUSE, fee, info);
    }

    private static void authenticate(String value) {
        SecurityContextHolder.getContext().setAuthentication(token(value));
    }

    private static JwtAuthenticationToken token(String value) {
        Jwt jwt = Jwt.withTokenValue(value).header("alg", "RS256").subject(UUID.randomUUID().toString())
                .claim("roles", List.of("SUPPORT_STAFF")).claim("privileges", List.of("SHIPPING.CREATE_SHIPMENT")).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_SUPPORT_STAFF"),
                new SimpleGrantedAuthority("SHIPPING.CREATE_SHIPMENT")));
    }

    private static Request request() {
        return Request.create(Request.HttpMethod.GET, "http://commerce-fixture/internal/v1/orders/" + ORDER + "/shipping-snapshot",
                Map.of(), new byte[0], StandardCharsets.UTF_8, new RequestTemplate());
    }

    private static FeignException feignFailure(int status, String code) {
        return FeignException.errorStatus("getPaidOrder", Response.builder().status(status).reason("fixture")
                .request(request()).headers(Map.of())
                .body("{\"code\":\"" + code + "\"}", StandardCharsets.UTF_8).build());
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).getErrorCode()).isEqualTo(code);
    }
}
