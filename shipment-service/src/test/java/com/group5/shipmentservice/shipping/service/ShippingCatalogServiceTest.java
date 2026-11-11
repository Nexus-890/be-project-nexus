package com.group5.shipmentservice.shipping.service;

import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.shipmentservice.shared.config.ShippingProperties;
import com.group5.shipmentservice.shipping.client.CatalogClient;
import com.group5.shipmentservice.shipping.dto.request.ShippingItemRequest;
import com.group5.shipmentservice.shipping.dto.response.CatalogSkuResponse;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import com.group5.shipmentservice.shipping.resilience.ShippingDeadline;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import feign.codec.DecodeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ShippingCatalogServiceTest {
    private static final UUID FIRST_SKU = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SECOND_SKU = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private final CatalogClient catalog = mock(CatalogClient.class);

    @Test
    void calculatesCurrentSubtotalAndReadsEachDistinctSkuOnce() {
        when(catalog.getSkuById(eq(FIRST_SKU), any())).thenReturn(sku(FIRST_SKU, "90000.25", "ACTIVE"));
        when(catalog.getSkuById(eq(SECOND_SKU), any())).thenReturn(sku(SECOND_SKU, "10000", "ACTIVE"));

        BigDecimal subtotal = service(5).getSubtotal(List.of(new ShippingItemRequest(FIRST_SKU, 2),
                new ShippingItemRequest(FIRST_SKU, 3), new ShippingItemRequest(SECOND_SKU, 1)), deadline());

        assertThat(subtotal).isEqualByComparingTo("460001.25");
        verify(catalog).getSkuById(eq(FIRST_SKU), any());
        verify(catalog).getSkuById(eq(SECOND_SKU), any());
        verifyNoMoreInteractions(catalog);
    }

    @Test
    void recalculatesPricesOnANewQuoteInsteadOfReusingPreviousPrices() {
        when(catalog.getSkuById(eq(FIRST_SKU), any()))
                .thenReturn(sku(FIRST_SKU, "90000", "ACTIVE"), sku(FIRST_SKU, "100000", "ACTIVE"));
        ShippingCatalogService service = service(5);

        assertThat(service.getSubtotal(items(), deadline())).isEqualByComparingTo("180000");
        assertThat(service.getSubtotal(items(), deadline())).isEqualByComparingTo("200000");

        verify(catalog, times(2)).getSkuById(eq(FIRST_SKU), any());
    }

    @Test
    void passesRemainingDeadlineAsPerCallTimeoutWithoutRedirects() {
        when(catalog.getSkuById(eq(FIRST_SKU), any())).thenReturn(sku(FIRST_SKU, "1000", "ACTIVE"));

        service(5).getSubtotal(items(), ShippingDeadline.after(Duration.ofMillis(800)));

        ArgumentCaptor<Request.Options> captured = ArgumentCaptor.forClass(Request.Options.class);
        verify(catalog).getSkuById(eq(FIRST_SKU), captured.capture());
        assertThat(captured.getValue().connectTimeoutMillis()).isBetween(1, 800);
        assertThat(captured.getValue().readTimeoutMillis()).isBetween(1, 800);
        assertThat(captured.getValue().isFollowRedirects()).isFalse();
    }

    @Test
    void missingSkuReturnsNamedNotFound() {
        when(catalog.getSkuById(eq(FIRST_SKU), any())).thenThrow(feignFailure(404));

        assertCode(() -> service(5).getSubtotal(items(), deadline()), "SHIPMENT_SKU_NOT_FOUND");
        verify(catalog).getSkuById(eq(FIRST_SKU), any());
    }

    @Test
    void inactiveSkuCannotReceiveAQuote() {
        when(catalog.getSkuById(eq(FIRST_SKU), any())).thenReturn(sku(FIRST_SKU, "1000", "INACTIVE"));

        assertCode(() -> service(5).getSubtotal(items(), deadline()), "SHIPMENT_SKU_NOT_FOUND");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void missingStatusIsAnInvalidUpstreamResponse(String status) {
        when(catalog.getSkuById(eq(FIRST_SKU), any())).thenReturn(sku(FIRST_SKU, "1000", status));

        assertCode(() -> service(5).getSubtotal(items(), deadline()), "SHIPMENT_INVALID_CATALOG_RESPONSE");
    }

    @Test
    void missingEnvelopeIsRejected() {
        when(catalog.getSkuById(eq(FIRST_SKU), any())).thenReturn(null);

        assertCode(() -> service(5).getSubtotal(items(), deadline()), "SHIPMENT_INVALID_CATALOG_RESPONSE");
    }

    @Test
    void missingDataIsRejected() {
        when(catalog.getSkuById(eq(FIRST_SKU), any())).thenReturn(ApiResponse.of(null));

        assertCode(() -> service(5).getSubtotal(items(), deadline()), "SHIPMENT_INVALID_CATALOG_RESPONSE");
    }

    @Test
    void errorEnvelopeCannotBeUsedAsAValidPrice() {
        when(catalog.getSkuById(eq(FIRST_SKU), any())).thenReturn(new ApiResponse<>(1, "Không thành công",
                new CatalogSkuResponse(FIRST_SKU, new BigDecimal("1000"), "ACTIVE"), null));

        assertCode(() -> service(5).getSubtotal(items(), deadline()), "SHIPMENT_INVALID_CATALOG_RESPONSE");
    }

    @Test
    void mismatchedSkuIdentityIsRejected() {
        when(catalog.getSkuById(eq(FIRST_SKU), any())).thenReturn(sku(SECOND_SKU, "1000", "ACTIVE"));

        assertCode(() -> service(5).getSubtotal(items(), deadline()), "SHIPMENT_INVALID_CATALOG_RESPONSE");
    }

    @Test
    void missingSkuIdentityIsRejected() {
        when(catalog.getSkuById(eq(FIRST_SKU), any())).thenReturn(sku(null, "1000", "ACTIVE"));

        assertCode(() -> service(5).getSubtotal(items(), deadline()), "SHIPMENT_INVALID_CATALOG_RESPONSE");
    }

    @Test
    void missingPriceIsNotTreatedAsZero() {
        when(catalog.getSkuById(eq(FIRST_SKU), any())).thenReturn(ApiResponse.of(
                new CatalogSkuResponse(FIRST_SKU, null, "ACTIVE")));

        assertCode(() -> service(5).getSubtotal(items(), deadline()), "SHIPMENT_INVALID_CATALOG_RESPONSE");
    }

    @Test
    void negativePriceIsRejected() {
        when(catalog.getSkuById(eq(FIRST_SKU), any())).thenReturn(sku(FIRST_SKU, "-1", "ACTIVE"));

        assertCode(() -> service(5).getSubtotal(items(), deadline()), "SHIPMENT_INVALID_CATALOG_RESPONSE");
    }

    @Test
    void zeroPriceRemainsAValidCatalogPrice() {
        when(catalog.getSkuById(eq(FIRST_SKU), any())).thenReturn(sku(FIRST_SKU, "0", "ACTIVE"));

        assertThat(service(5).getSubtotal(items(), deadline())).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 500, 503})
    void transportFailuresReturnUnavailableWithoutRetry(int status) {
        when(catalog.getSkuById(eq(FIRST_SKU), any())).thenThrow(feignFailure(status));

        assertCode(() -> service(5).getSubtotal(items(), deadline()), "SHIPMENT_CATALOG_UNAVAILABLE");
        verify(catalog).getSkuById(eq(FIRST_SKU), any());
    }

    @Test
    void decodeFailureReturnsNamedBadGateway() {
        when(catalog.getSkuById(eq(FIRST_SKU), any())).thenThrow(
                new DecodeException(200, "Dữ liệu kiểm thử không hợp lệ", feignRequest()));

        assertCode(() -> service(5).getSubtotal(items(), deadline()), "SHIPMENT_INVALID_CATALOG_RESPONSE");
        verify(catalog).getSkuById(eq(FIRST_SKU), any());
    }

    @Test
    void timeoutReturnsUnavailableWithoutServiceLevelRetry() {
        when(catalog.getSkuById(eq(FIRST_SKU), any())).thenThrow(new RetryableException(
                -1, "Hết thời gian kiểm thử", Request.HttpMethod.GET, (Long) null, feignRequest()));

        assertCode(() -> service(5).getSubtotal(items(), deadline()), "SHIPMENT_CATALOG_UNAVAILABLE");
        verify(catalog).getSkuById(eq(FIRST_SKU), any());
    }

    @Test
    void openCatalogBreakerFailsFastBeforeCallingFeign() {
        when(catalog.getSkuById(eq(FIRST_SKU), any())).thenThrow(feignFailure(503));
        ShippingCatalogService service = service(1);
        assertCode(() -> service.getSubtotal(items(), deadline()), "SHIPMENT_CATALOG_UNAVAILABLE");
        clearInvocations(catalog);

        assertCode(() -> service.getSubtotal(items(), deadline()), "SHIPMENT_CIRCUIT_OPEN");
        verifyNoInteractions(catalog);
    }

    @Test
    void invalidCatalogDataDoesNotOpenTheAvailabilityBreaker() {
        when(catalog.getSkuById(eq(FIRST_SKU), any())).thenReturn(null, sku(FIRST_SKU, "1000", "ACTIVE"));
        ShippingCatalogService service = service(1);

        assertCode(() -> service.getSubtotal(items(), deadline()), "SHIPMENT_INVALID_CATALOG_RESPONSE");
        assertThat(service.getSubtotal(items(), deadline())).isEqualByComparingTo("2000");
        verify(catalog, times(2)).getSkuById(eq(FIRST_SKU), any());
    }

    private ShippingCatalogService service(int failureThreshold) {
        ShippingProperties properties = new ShippingProperties(ShippingProperties.CarrierMode.FAKE,
                3000, 3, 0, new BigDecimal("1000000"), failureThreshold, 30000,
                "", null, null, Map.of());
        return new ShippingCatalogService(catalog, new CarrierExecutor(properties));
    }

    private ApiResponse<CatalogSkuResponse> sku(UUID id, String price, String status) {
        return ApiResponse.of(new CatalogSkuResponse(id, new BigDecimal(price), status));
    }

    private List<ShippingItemRequest> items() {
        return List.of(new ShippingItemRequest(FIRST_SKU, 2));
    }

    private ShippingDeadline deadline() {
        return ShippingDeadline.after(Duration.ofSeconds(3));
    }

    private FeignException feignFailure(int status) {
        return FeignException.errorStatus("CatalogClient#getSkuById", Response.builder()
                .status(status).reason("Lỗi kiểm thử").request(feignRequest()).build());
    }

    private Request feignRequest() {
        return Request.create(Request.HttpMethod.GET, "http://catalog-service/api/v1/skus/" + FIRST_SKU,
                Map.of(), (byte[]) null, StandardCharsets.UTF_8, null);
    }

    private void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
