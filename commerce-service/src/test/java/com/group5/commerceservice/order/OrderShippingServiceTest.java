package com.group5.commerceservice.order;

import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.commerceservice.cart.dto.CatalogSkuResponse;
import com.group5.commerceservice.order.client.OrderRemoteExecutor;
import com.group5.commerceservice.order.client.ShippingQuoteHttpClient;
import com.group5.commerceservice.order.dto.request.CreateOrderRequest.ShippingAddress;
import com.group5.commerceservice.order.service.OrderShippingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderShippingServiceTest {
    private final ShippingQuoteHttpClient http = mock(ShippingQuoteHttpClient.class);
    private final OrderShippingService service = new OrderShippingService(http, new OrderRemoteExecutor(5, 30000),
            JsonMapper.builder().build());
    private final ShippingAddress address = new ShippingAddress("Buyer", "0901234567", "1 Main Street",
            "1003646", "1000001", "VN");

    @Test
    void packsEveryQuantityFromSellerOwnedMetadata() {
        var first = line(2, 1000, 30, 20, 10);
        var second = line(3, 500, 40, 15, 5);
        assertThat(service.pack(List.of(first, second)))
                .isEqualTo(new OrderShippingService.Parcel(3500, 40, 20, 35));
        verifyNoInteractions(http);
    }

    @Test
    void missingMetadataNamesSkuAndNeverCallsShipping() {
        UUID sku = UUID.randomUUID();
        assertThatThrownBy(() -> service.quote(address,
                List.of(new OrderShippingService.Line(sku, 1, new CatalogSkuResponse()))))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo("COMMERCE_SHIPPING_METADATA_REQUIRED");
                    assertThat(error.getProperties()).containsEntry("skuId", sku);
                });
        verifyNoInteractions(http);
    }

    @Test
    void rejectsParcelOutsideCarrierLimitsWithoutTruncation() {
        for (var lines : List.of(List.of(line(2, 30000, 10, 10, 1)),
                List.of(line(2, 1, 10, 10, 101)), List.of(line(1, 1, 201, 10, 10)),
                List.of(line(999, 50000, 200, 200, 200)), List.of(line(0, 1, 1, 1, 1)))) {
            assertThatThrownBy(() -> service.pack(lines)).isInstanceOfSatisfying(DomainException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo("COMMERCE_INVALID_PARCEL"));
        }
        verifyNoInteractions(http);
    }

    @Test
    void quotesPackedSellerOrderUsingRealRecipientAndDefaultWarehouse() {
        var eta = OffsetDateTime.now().plusDays(2);
        when(http.quote(any())).thenReturn(ApiResponse.list(List.of(
                new ShippingQuoteHttpClient.Response("GHN", "STANDARD", new BigDecimal("30000"), eta),
                new ShippingQuoteHttpClient.Response("GHN", "STANDARD", new BigDecimal("25000"), eta))));
        var line = line(2, 1200, 30, 20, 15);
        var quote = service.quote(address, List.of(line));
        assertThat(quote.fee()).isEqualByComparingTo("25000.00");
        assertThat(quote.parcel()).isEqualTo(new OrderShippingService.Parcel(2400, 30, 20, 30));
        var sent = ArgumentCaptor.forClass(ShippingQuoteHttpClient.Request.class);
        verify(http).quote(sent.capture());
        assertThat(sent.getValue().warehouseId()).isNull();
        assertThat(sent.getValue().toAddress()).isEqualTo(new ShippingQuoteHttpClient.Address(
                "1000001", "1003646", "1 Main Street", "Buyer", "0901234567"));
        assertThat(sent.getValue().items()).containsExactly(new ShippingQuoteHttpClient.Item(line.skuId(), 2));
    }

    @Test
    void carrierStillMustAnswerBeforeFreeShippingIsAccepted() {
        when(http.quote(any())).thenReturn(ApiResponse.list(List.of(new ShippingQuoteHttpClient.Response(
                "GHN", "STANDARD", BigDecimal.ZERO, OffsetDateTime.now().plusDays(1)))));
        assertThat(service.quote(address, List.of(line(1, 1, 1, 1, 1))).fee()).isEqualByComparingTo("0.00");
        verify(http).quote(any());
    }

    @Test
    void rejectsFractionalNegativeMissingAndWrongServiceQuotes() {
        var eta = OffsetDateTime.now().plusDays(1);
        for (var quote : List.of(new ShippingQuoteHttpClient.Response("GHN", "STANDARD", new BigDecimal("1.5"), eta),
                new ShippingQuoteHttpClient.Response("GHN", "STANDARD", new BigDecimal("-1"), eta),
                new ShippingQuoteHttpClient.Response("GHN", "HEAVY", BigDecimal.ONE, eta),
                new ShippingQuoteHttpClient.Response("", "STANDARD", BigDecimal.ONE, eta),
                new ShippingQuoteHttpClient.Response("GHN", "STANDARD", BigDecimal.ONE, null))) {
            when(http.quote(any())).thenReturn(ApiResponse.list(List.of(quote)));
            assertThatThrownBy(() -> service.quote(address, List.of(line(1, 1, 1, 1, 1))))
                    .isInstanceOfSatisfying(DomainException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo("COMMERCE_SHIPPING_UNAVAILABLE"));
        }
    }

    @Test
    void twentyKilogramsUsesHeavyService() {
        when(http.quote(any())).thenReturn(ApiResponse.list(List.of(new ShippingQuoteHttpClient.Response(
                "GHN", "HEAVY", BigDecimal.ONE, OffsetDateTime.now().plusDays(1)))));
        assertThat(service.quote(address, List.of(line(2, 10000, 1, 1, 1))).serviceType()).isEqualTo("HEAVY");
    }

    private static OrderShippingService.Line line(int quantity, int weight, int length, int width, int height) {
        var sku = new CatalogSkuResponse();
        sku.setShippingParcel(new CatalogSkuResponse.ShippingParcel(weight, length, width, height));
        return new OrderShippingService.Line(UUID.randomUUID(), quantity, sku);
    }
}
