package com.group5.shipmentservice.shipping.adapter.ghn;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.config.GhnProperties;
import com.group5.shipmentservice.shared.config.ShippingProperties;
import com.group5.shipmentservice.shipping.adapter.model.CarrierAddress;
import com.group5.shipmentservice.shipping.adapter.model.CarrierQuoteRequest;
import com.group5.shipmentservice.shipping.adapter.model.CarrierShipmentRequest;
import com.group5.shipmentservice.shipping.dto.request.ParcelRequest;
import com.group5.shipmentservice.shipping.dto.response.LocalityResponse;
import com.group5.shipmentservice.shipping.enums.ShippingServiceType;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import com.group5.shipmentservice.shipping.resilience.ShippingDeadline;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GhnAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ExecutorService workers;
    private Function<CapturedRequest, StubResponse> response;
    private boolean delayBody;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        response = request -> success("[]");
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        workers.shutdownNow();
    }

    @Test
    void booksPaidSnapshotWithStableClientCodeFullAddressAndReturnedEstimate() {
        response = this::bookingResponse;
        CarrierShipmentRequest snapshot = shipmentRequest();
        var result = adapter(3000, 3).bookShipment(snapshot, deadline());
        assertThat(result.trackingCode()).isEqualTo("GHN123456");
        assertThat(result.estimatedDeliveryAt().toInstant()).isEqualTo(Instant.parse("2026-11-20T16:59:59Z"));
        assertThat(result.addressFallback()).isFalse();
        var create = requests.stream().filter(request -> request.path().endsWith("/create")).toList();
        assertThat(create).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.token()).isEqualTo("token-kiem-thu");
            assertThat(request.shopId()).isEqualTo("12345");
            JsonNode body = mapper.readTree(request.body());
            assertThat(body.path("client_order_code").asString()).isEqualTo(snapshot.requestId().toString());
            assertThat(body.path("to_name").asString()).isEqualTo("Nguyễn Văn An");
            assertThat(body.path("to_phone").asString()).isEqualTo("0901234567");
            assertThat(body.path("to_address").asString()).isEqualTo("12 Lê Lợi, Phường Sài Gòn, Hồ Chí Minh");
            assertThat(body.path("to_ward_name").asString()).isEqualTo("Phường Sài Gòn");
            assertThat(body.path("to_province_name").asString()).isEqualTo("Hồ Chí Minh");
            assertThat(body.path("is_new_to_address").asBoolean()).isTrue();
            assertThat(body.path("is_new_from_address").asBoolean()).isTrue();
            assertThat(body.path("from_address").asString()).isEqualTo("20 Nguyễn Huệ, Phường Sài Gòn, Hồ Chí Minh");
            assertThat(body.path("weight").asInt()).isEqualTo(1200);
            assertThat(body.path("length").asInt()).isEqualTo(30);
            assertThat(body.path("width").asInt()).isEqualTo(20);
            assertThat(body.path("height").asInt()).isEqualTo(15);
            assertThat(body.path("service_type_id").asInt()).isEqualTo(2);
            assertThat(body.path("payment_type_id").asInt()).isEqualTo(1);
            assertThat(body.path("required_note").asString()).isEqualTo("KHONGCHOXEMHANG");
            assertThat(body.path("cod_amount").asInt()).isZero();
            assertThat(body.path("items").get(0).path("name").asString()).isEqualTo("Áo thun");
            assertThat(body.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
            assertThat(request.body()).doesNotContain("district");
        });
        assertThat(requests.getFirst().path()).endsWith("/detail-by-client-code");
    }

    @Test
    void bookingTimeoutLooksUpThePersistedCodeBeforeRetryAndCreatesOnlyOneWaybill() {
        AtomicInteger creates = new AtomicInteger();
        CarrierShipmentRequest snapshot = shipmentRequest();
        response = request -> {
            if (request.path().endsWith("/create")) {
                creates.incrementAndGet(); // GHN committed the order, but the response is lost.
                return new StubResponse(200, bookingResponse(request).body(), 600, null);
            }
            if (request.path().endsWith("/detail-by-client-code") && creates.get() > 0) {
                assertThat(request.query()).isEqualTo("client_order_code=" + snapshot.requestId());
                return success("{\"order_code\":\"GHN123456\",\"client_order_code\":\"" + snapshot.requestId()
                        + "\",\"leadtime\":\"2026-11-20T16:59:59Z\"}");
            }
            return bookingResponse(request);
        };
        var result = adapter(shipping(3000, 3, 5, 30000), 150, 300000).bookShipment(snapshot, deadline());
        assertThat(result.trackingCode()).isEqualTo("GHN123456");
        assertThat(result.estimatedDeliveryAt().toInstant()).isEqualTo(Instant.parse("2026-11-20T16:59:59Z"));
        assertThat(creates).hasValue(1);
        assertThat(requests.stream().filter(request -> request.path().endsWith("/detail-by-client-code"))).hasSize(2);
        assertThat(requests.getLast().path()).endsWith("/detail-by-client-code");
    }

    @Test
    void retryAfterRestartFindsExistingWaybillWithoutPostingAgainAndRecoversFallbackFact() {
        CarrierShipmentRequest snapshot = shipmentRequest();
        response = request -> success("{\"order_code\":\"GHN123456\",\"client_order_code\":\"" + snapshot.requestId()
                + "\",\"note\":\"" + GhnAdapter.ADDRESS_FALLBACK_NOTE + "\"}");
        var result = adapter(3000, 3).bookShipment(snapshot, deadline());
        assertThat(result.trackingCode()).isEqualTo("GHN123456");
        assertThat(result.estimatedDeliveryAt()).isNull();
        assertThat(result.addressFallback()).isTrue();
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.path()).endsWith("/detail-by-client-code");
        });
    }

    @Test
    void transientCreateRetryKeepsClientCodeAndAlwaysLooksUpBeforePosting() {
        AtomicInteger creates = new AtomicInteger();
        response = request -> request.path().endsWith("/create") && creates.incrementAndGet() == 1
                ? new StubResponse(503, "{}", 0, null) : bookingResponse(request);
        CarrierShipmentRequest snapshot = shipmentRequest();
        assertThat(adapter(3000, 3).createShipment(snapshot, deadline())).isEqualTo("GHN123456");
        var mutations = requests.stream().filter(request -> request.path().endsWith("/create")
                || request.path().endsWith("/detail-by-client-code")).toList();
        assertThat(mutations).hasSize(4);
        assertThat(mutations.get(0).path()).endsWith("/detail-by-client-code");
        assertThat(mutations.get(1).path()).endsWith("/create");
        assertThat(mutations.get(2).path()).endsWith("/detail-by-client-code");
        assertThat(mutations.get(3).path()).endsWith("/create");
        assertThat(mapper.readTree(mutations.get(1).body()).path("client_order_code").asString())
                .isEqualTo(snapshot.requestId().toString());
        assertThat(mutations.get(3).body()).isEqualTo(mutations.get(1).body());
    }

    @Test
    void addressConflictRetriesOnceWithAreaOnlyAndReturnsHistoryFact() {
        AtomicInteger creates = new AtomicInteger();
        response = request -> request.path().endsWith("/create") && creates.incrementAndGet() == 1
                ? addressConflict() : bookingResponse(request);
        var result = adapter(3000, 3).bookShipment(shipmentRequest(), deadline());
        assertThat(result.addressFallback()).isTrue();
        var posts = requests.stream().filter(request -> request.path().endsWith("/create")).toList();
        assertThat(posts).hasSize(2);
        JsonNode full = mapper.readTree(posts.getFirst().body());
        JsonNode fallback = mapper.readTree(posts.getLast().body());
        assertThat(full.path("to_address").asString()).isEqualTo("12 Lê Lợi, Phường Sài Gòn, Hồ Chí Minh");
        assertThat(fallback.path("to_address").asString()).isEqualTo("Phường Sài Gòn, Hồ Chí Minh");
        assertThat(fallback.path("note").asString()).isEqualTo(GhnAdapter.ADDRESS_FALLBACK_NOTE);
        assertThat(fallback.path("client_order_code")).isEqualTo(full.path("client_order_code"));
    }

    @Test
    void aSecondAddressConflictStopsInsteadOfLooping() {
        response = request -> request.path().endsWith("/create") ? addressConflict() : bookingResponse(request);
        assertCode(() -> adapter(3000, 3).bookShipment(shipmentRequest(), deadline()), "SHIPMENT_INVALID_ADDRESS");
        assertThat(requests.stream().filter(request -> request.path().endsWith("/create"))).hasSize(2);
    }

    @Test
    void createFiveHundredsMapToCarrierUnavailableAndStopAtRetryLimit() {
        response = request -> request.path().endsWith("/create")
                ? new StubResponse(503, "{\"message\":\"provider-private-message\"}", 0, null) : bookingResponse(request);
        assertThatThrownBy(() -> adapter(3000, 1).bookShipment(shipmentRequest(), deadline()))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).getErrorCode()).isEqualTo("SHIPMENT_CARRIER_UNAVAILABLE"))
                .hasMessageNotContaining("provider-private-message");
        assertThat(requests.stream().filter(request -> request.path().endsWith("/create"))).hasSize(2);
    }

    @Test
    void lookupPermissionFailureNeverBecomesPermissionToCreateAnotherWaybill() {
        response = request -> new StubResponse(400,
                "{\"code\":400,\"code_message\":\"CLIENT_NOT_OWNER_OF_SHOP\",\"data\":null}", 0, null);
        assertCode(() -> adapter(3000, 3).bookShipment(shipmentRequest(), deadline()), "SHIPMENT_CARRIER_CONFIGURATION_ERROR");
        assertThat(requests).singleElement().satisfies(request -> assertThat(request.method()).isEqualTo("GET"));
    }

    @Test
    void rejectsProductionBookingBeforeAnyNetworkRequest() {
        var properties = new GhnProperties(URI.create("https://online-gateway.ghn.vn"), 12345,
                1, GhnProperties.RequiredNote.KHONGCHOXEMHANG, 1500, 0);
        var production = new GhnAdapter(properties, shipping(3000, 3, 5, 30000),
                new MockEnvironment().withProperty("GHN_TOKEN", "token-kiem-thu"), mapper);
        assertCode(() -> production.bookShipment(shipmentRequest(), deadline()), "SHIPMENT_CARRIER_CONFIGURATION_ERROR");
        assertThat(requests).isEmpty();
    }

    @Test
    void heavyBookingUsesSingleMeasuredParcelAndRoundsSkuDimensionsUp() {
        response = this::bookingResponse;
        CarrierShipmentRequest light = shipmentRequest();
        Map<String, Object> info = new LinkedHashMap<>(light.shippingInfo());
        info.put("parcel", Map.of("weightGrams", 20000, "lengthCm", new BigDecimal("30.2"),
                "widthCm", 20, "heightCm", new BigDecimal("15.01")));
        adapter(3000, 3).bookShipment(new CarrierShipmentRequest(light.requestId(), light.orderId(), light.warehouseId(),
                "HEAVY", light.shippingFee(), info), deadline());
        JsonNode body = mapper.readTree(requests.getLast().body());
        assertThat(body.path("service_type_id").asInt()).isEqualTo(5);
        assertThat(body.path("length").asInt()).isEqualTo(31);
        assertThat(body.path("height").asInt()).isEqualTo(16);
        assertThat(body.path("items")).hasSize(1);
        assertThat(body.path("items").get(0).path("quantity").asInt()).isEqualTo(1);
        assertThat(body.path("items").get(0).path("weight").asInt()).isEqualTo(20000);
        assertThat(body.path("items").get(0).path("length").asInt()).isEqualTo(31);
    }

    private CarrierShipmentRequest shipmentRequest() {
        return new CarrierShipmentRequest(UUID.fromString("d1000000-0000-4000-8000-000000000001"),
                UUID.fromString("d2000000-0000-4000-8000-000000000001"), UUID.randomUUID(), "STANDARD", new BigDecimal("27500"),
                Map.of("recipient", Map.of("name", "Nguyễn Văn An", "phone", "0901234567", "address",
                                Map.of("line1", "12 Lê Lợi", "provinceCode", "1000001", "wardCode", "1003646")),
                        "pickupAddress", Map.of("line1", "20 Nguyễn Huệ", "provinceCode", "1000001", "wardCode", "1003646"),
                        "parcel", Map.of("weightGrams", 1200, "lengthCm", 30, "widthCm", 20, "heightCm", 15),
                        "items", List.of(Map.of("skuId", UUID.randomUUID().toString(), "productName", "Áo thun", "quantity", 2))));
    }

    private StubResponse bookingResponse(CapturedRequest request) {
        if (request.path().endsWith("/province/all")) {
            return success("[" + locality(1000001, "Hồ Chí Minh", "province", 1, 1) + "]");
        }
        if (request.path().endsWith("/ward/all-by-province-id")) {
            return success("[" + locality(1003646, "Phường Sài Gòn", "ward", 1000001, 1) + "]");
        }
        if (request.path().endsWith("/detail-by-client-code")) {
            return new StubResponse(400, "{\"code\":400,\"message\":\"Đơn hàng không tồn tại\",\"data\":null}", 0, null);
        }
        return success("{\"order_code\":\"GHN123456\",\"expected_delivery_time\":\"2026-11-20T16:59:59Z\"}");
    }

    private StubResponse addressConflict() {
        return new StubResponse(400, "{\"code\":400,\"code_message\":\"TO_ADDRESS_CONFLICT\","
                + "\"message\":\"provider-private-address-conflict\",\"data\":null}", 0, null);
    }

    @Test
    void mapsActiveV3ProvinceCodesAndCanonicalNames() {
        response = request -> success("["
                + locality(1000001, "Hồ Chí Minh", "province", 1, 1) + ","
                + locality(1000002, "Tỉnh ngưng sử dụng", "province", 1, 2) + ","
                + locality(1000003, "Tỉnh đã xoá", "province", 1, 10) + "]");
        assertThat(adapter(3000, 3).getProvinces(deadline()))
                .containsExactly(new LocalityResponse("1000001", "Hồ Chí Minh"));
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.path()).isEqualTo("/shiip/public-api/v3/master-data/province/all");
            assertThat(request.query()).isEqualTo("offset=0&limit=200");
            assertThat(request.token()).isEqualTo("token-kiem-thu");
            assertThat(request.shopId()).isEqualTo("12345");
        });
    }

    @Test
    void wardsBelongDirectlyToRequestedProvinceAndInactiveWardsAreExcluded() {
        response = request -> success("["
                + locality(1003646, "Phường Vũng Tàu", "ward", 1000001, 1) + ","
                + locality(1003647, "Phường tỉnh khác", "ward", 1000002, 1) + ","
                + locality(1003648, "Phường ngưng sử dụng", "ward", 1000001, 2) + "]");
        assertThat(adapter(3000, 3).getWards("1000001", deadline()))
                .containsExactly(new LocalityResponse("1003646", "Phường Vũng Tàu"));
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo("/shiip/public-api/v3/master-data/ward/all-by-province-id");
            assertThat(request.query()).isEqualTo("province_id=1000001&offset=0&limit=200");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"_id\":1003646,\"name\":\"Phường\",\"type\":\"ward\",\"status\":1}",
            "{\"_id\":1003646,\"name\":\"Phường\",\"type\":\"district\",\"parent_id\":1000001,\"status\":1}",
            "{\"_id\":1003646,\"name\":\"Phường\",\"type\":\"ward\",\"parent_id\":1000001}",
            "{\"_id\":1003646,\"name\":\"Phường\",\"type\":\"ward\",\"parent_id\":1000001,\"status\":0}",
            "{\"_id\":\"1003646\",\"name\":\"Phường\",\"type\":\"ward\",\"parent_id\":1000001,\"status\":1}",
            "{\"_id\":0,\"name\":\"Phường\",\"type\":\"ward\",\"parent_id\":1000001,\"status\":1}",
            "{\"_id\":1003646,\"name\":\"\",\"type\":\"ward\",\"parent_id\":1000001,\"status\":1}"
    })
    void malformedV3LocalityIsRejected(String item) {
        response = request -> success("[" + item + "]");
        assertCode(() -> adapter(3000, 3).getWards("1000001", deadline()), "SHIPMENT_INVALID_CARRIER_RESPONSE");
        assertThat(requests).hasSize(1);
    }

    @Test
    void nullWardDataMeansUnknownProvince() {
        response = request -> success("null");
        assertCode(() -> adapter(3000, 3).getWards("1000001", deadline()), "SHIPMENT_INVALID_ADDRESS");
        assertThat(requests).hasSize(1);
    }

    @Test
    void nullProvinceDataIsMalformedProviderResponse() {
        response = request -> success("null");
        assertCode(() -> adapter(3000, 3).getProvinces(deadline()), "SHIPMENT_INVALID_CARRIER_RESPONSE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "001000001", "-1", "chu", "2147483648"})
    void invalidProvinceCodeIsRejectedBeforeHttpCall(String code) {
        assertCode(() -> adapter(3000, 3).getWards(code, deadline()), "SHIPMENT_INVALID_ADDRESS");
        assertThat(requests).isEmpty();
    }

    @Test
    void previewLeavesBuyerStreetLineOutSoItCannotConflictWithChosenWard() {
        response = request -> previewResponse();
        CarrierQuoteRequest base = quoteRequest();
        CarrierQuoteRequest conflicting = new CarrierQuoteRequest(base.pickup(),
                new CarrierAddress("Hồ Chí Minh", "Phường Sài Gòn", "12 Lê Lợi, Phường Bến Thành, Quận 1, Hà Nội"),
                base.parcel(), base.insuranceValue(), base.recipientName(), base.recipientPhone(), base.content());
        adapter(3000, 3).quote(conflicting, deadline());
        assertThat(requests).singleElement().satisfies(request -> {
            JsonNode body = mapper.readTree(request.body());
            assertThat(body.path("to_address").asString()).isEqualTo("Phường Sài Gòn, Hồ Chí Minh");
            assertThat(request.body()).doesNotContain("Lê Lợi", "Bến Thành", "Hà Nội");
        });
    }

    @Test
    void previewQuotesNewAddressWithoutDistrictFieldsOrOrderCreation() {
        response = request -> previewResponse();
        var quotes = adapter(3000, 3).quote(quoteRequest(), deadline());
        assertThat(quotes).singleElement().satisfies(quote -> {
            assertThat(quote.carrier()).isEqualTo("GHN");
            assertThat(quote.serviceType()).isEqualTo(ShippingServiceType.STANDARD);
            assertThat(quote.fee()).isEqualByComparingTo("27500");
            assertThat(quote.estimatedDeliveryAt().toInstant()).isEqualTo(Instant.parse("2026-11-15T16:59:59Z"));
        });
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/shiip/public-api/v2/shipping-order/preview");
            JsonNode body = mapper.readTree(request.body());
            assertThat(body.path("is_new_from_address").booleanValue()).isTrue();
            assertThat(body.path("from_province_name").asString()).isEqualTo("Hồ Chí Minh");
            assertThat(body.path("from_ward_name").asString()).isEqualTo("Phường Tân Phú");
            assertThat(body.path("from_address").asString()).isEqualTo("Địa chỉ lấy hàng, Phường Tân Phú, Hồ Chí Minh");
            assertThat(body.path("is_new_to_address").booleanValue()).isTrue();
            assertThat(body.path("to_province_name").asString()).isEqualTo("Hồ Chí Minh");
            assertThat(body.path("to_ward_name").asString()).isEqualTo("Phường Sài Gòn");
            assertThat(body.path("to_address").asString()).isEqualTo("Phường Sài Gòn, Hồ Chí Minh");
            assertThat(body.path("to_name").asString()).isEqualTo("Nguyễn Văn An");
            assertThat(body.path("to_phone").asString()).isEqualTo("0901234567");
            assertThat(body.path("payment_type_id").intValue()).isEqualTo(1);
            assertThat(body.path("required_note").asString()).isEqualTo("KHONGCHOXEMHANG");
            assertThat(body.path("service_type_id").intValue()).isEqualTo(2);
            assertThat(body.path("weight").intValue()).isEqualTo(1200);
            assertThat(body.path("length").intValue()).isEqualTo(30);
            assertThat(body.path("insurance_value").intValue()).isEqualTo(250000);
            assertThat(body.path("content").asString()).isEqualTo("3 sản phẩm, 2 mã SKU, 1 kiện hàng");
            assertThat(body.path("cod_amount").intValue()).isZero();
            assertThat(body.has("items")).isFalse();
            assertThat(request.body()).doesNotContain("district", "ward_code", "province_id", "service_id", "client_order_code");
        });
    }

    @Test
    void heavyParcelUsesVerifiedMeasurementsAndExactInsurance() {
        response = request -> previewResponse();
        CarrierQuoteRequest light = quoteRequest();
        CarrierQuoteRequest heavy = new CarrierQuoteRequest(light.pickup(), light.toAddress(),
                new ParcelRequest(20000, 60, 40, 40), BigDecimal.valueOf(5_000_000),
                light.recipientName(), light.recipientPhone(), light.content());
        assertThat(adapter(3000, 3).quote(heavy, deadline()).getFirst().serviceType()).isEqualTo(ShippingServiceType.HEAVY);
        JsonNode body = mapper.readTree(requests.getFirst().body());
        assertThat(body.path("insurance_value").longValue()).isEqualTo(5_000_000);
        assertThat(body.path("items").get(0).path("quantity").intValue()).isEqualTo(1);
        assertThat(body.path("items").get(0).path("weight").intValue()).isEqualTo(20000);
        assertThat(body.path("items").get(0).path("length").intValue()).isEqualTo(60);
        assertThat(body.path("items").get(0).path("name").asString()).isEqualTo(light.content());
        assertThat(heavy.insuranceValue()).isEqualByComparingTo("5000000");
    }

    @ParameterizedTest
    @CsvSource({"19999,2,STANDARD", "20000,5,HEAVY", "20001,5,HEAVY"})
    void mapsProviderServiceIdsToPublicServiceTypesAtTheWeightBoundary(int weight, int providerId,
                                                                    ShippingServiceType serviceType) {
        response = request -> previewResponse();
        CarrierQuoteRequest baseline = quoteRequest();
        CarrierQuoteRequest request = new CarrierQuoteRequest(baseline.pickup(), baseline.toAddress(),
                new ParcelRequest(weight, 60, 40, 40), baseline.insuranceValue(),
                baseline.recipientName(), baseline.recipientPhone(), baseline.content());

        assertThat(adapter(3000, 3).quote(request, deadline())).singleElement()
                .satisfies(quote -> assertThat(quote.serviceType()).isEqualTo(serviceType));

        assertThat(requests).singleElement().satisfies(captured -> {
            JsonNode body = mapper.readTree(captured.body());
            assertThat(body.path("service_type_id").intValue()).isEqualTo(providerId);
            assertThat(body.path("weight").intValue()).isEqualTo(weight);
            assertThat(body.has("items")).isEqualTo(serviceType == ShippingServiceType.HEAVY);
        });
    }

    @Test
    void mapsConfiguredPayerAndInspectionPolicy() {
        response = request -> previewResponse();
        GhnProperties configured = new GhnProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                12345, 2, GhnProperties.RequiredNote.CHOXEMHANGKHONGTHU, 1500, 0);
        GhnAdapter adapter = new GhnAdapter(configured, shipping(3000, 3, 5, 30000),
                new MockEnvironment().withProperty("GHN_TOKEN", "token-kiem-thu"), mapper);
        adapter.quote(quoteRequest(), deadline());
        JsonNode body = mapper.readTree(requests.getFirst().body());
        assertThat(body.path("payment_type_id").intValue()).isEqualTo(2);
        assertThat(body.path("required_note").asString()).isEqualTo("CHOXEMHANGKHONGTHU");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "5000001", "250000.1"})
    void invalidInsuranceIsRejectedWithoutHttpInsteadOfClampedOrRounded(String value) {
        CarrierQuoteRequest request = quoteRequest();
        CarrierQuoteRequest invalid = new CarrierQuoteRequest(request.pickup(), request.toAddress(), request.parcel(),
                new BigDecimal(value), request.recipientName(), request.recipientPhone(), request.content());
        assertCode(() -> adapter(3000, 3).quote(invalid, deadline()), "SHIPMENT_INVALID_REQUEST");
        assertThat(requests).isEmpty();
    }

    @Test
    void absentInsuranceDefaultsToZero() {
        response = request -> previewResponse();
        CarrierQuoteRequest request = quoteRequest();
        CarrierQuoteRequest noInsurance = new CarrierQuoteRequest(request.pickup(), request.toAddress(), request.parcel(),
                null, request.recipientName(), request.recipientPhone(), request.content());
        adapter(3000, 3).quote(noInsurance, deadline());
        assertThat(mapper.readTree(requests.getFirst().body()).path("insurance_value").intValue()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"order_code\":\"CREATED\",\"total_fee\":27500,\"expected_delivery_time\":\"2026-11-15T16:59:59Z\"}",
            "{\"total_fee\":27500,\"expected_delivery_time\":\"2026-11-15T16:59:59Z\"}",
            "{\"order_code\":\"\",\"expected_delivery_time\":\"2026-11-15T16:59:59Z\"}",
            "{\"order_code\":\"\",\"total_fee\":-1,\"expected_delivery_time\":\"2026-11-15T16:59:59Z\"}",
            "{\"order_code\":\"\",\"total_fee\":27500}",
            "{\"order_code\":\"\",\"total_fee\":27500,\"expected_delivery_time\":1794761999}",
            "{\"order_code\":\"\",\"total_fee\":27500,\"expected_delivery_time\":\"khong-hop-le\"}"
    })
    void malformedPreviewNeverInventsFeeDateOrAcceptsIssuedTracking(String data) {
        response = request -> success(data);
        assertCode(() -> adapter(3000, 3).quote(quoteRequest(), deadline()), "SHIPMENT_INVALID_CARRIER_RESPONSE");
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROUTE_NOT_FOUND_SERVICE", "SERVICE_NOT_FOUND_CONFIG_FEE"})
    void unsupportedRouteReturnsNoServiceWithoutRetry(String code) {
        response = request -> new StubResponse(400, "{\"code\":400,\"code_message\":\"" + code + "\",\"data\":null}", 0, null);
        assertCode(() -> adapter(3000, 3).quote(quoteRequest(), deadline()), "SHIPMENT_NO_SERVICE");
        assertThat(requests).hasSize(1);
    }

    @Test
    void pickupAddressConflictIsItsOwnCodeAndNotRetried() {
        response = request -> new StubResponse(400,
                "{\"code\":400,\"code_message\":\"FROM_ADDRESS_CONFLICT\",\"data\":null}", 0, null);
        assertCode(() -> adapter(3000, 3).quote(quoteRequest(), deadline()), "SHIPMENT_PICKUP_ADDRESS_CONFLICT");
        assertThat(requests).hasSize(1);
    }

    @Test
    void routeFailureWithoutNamedProviderCodeIsStillNoService() {
        response = request -> new StubResponse(400,
                "{\"code\":400,\"message\":\"calculate fee error: route not found service\",\"data\":null}", 0, null);
        assertCode(() -> adapter(3000, 3).quote(quoteRequest(), deadline()), "SHIPMENT_NO_SERVICE");
    }

    @Test
    void phoneValidationIsInvalidRequestAndNotRetried() {
        response = request -> new StubResponse(400,
                "{\"code\":400,\"code_message\":\"PHONE_INVALID\",\"data\":null}", 0, null);
        assertCode(() -> adapter(3000, 3).quote(quoteRequest(), deadline()), "SHIPMENT_INVALID_REQUEST");
        assertThat(requests).hasSize(1);
    }

    @Test
    void retriesOnlyTransientPreviewFailure() {
        AtomicInteger calls = new AtomicInteger();
        response = request -> calls.incrementAndGet() < 3 ? new StubResponse(503, "{}", 0, null) : previewResponse();
        assertThat(adapter(3000, 3).quote(quoteRequest(), deadline())).hasSize(1);
        assertThat(requests).hasSize(3).allSatisfy(request ->
                assertThat(request.path()).isEqualTo("/shiip/public-api/v2/shipping-order/preview"));
    }

    @Test
    void retriesProviderFailureInsideHttp200Envelope() {
        AtomicInteger calls = new AtomicInteger();
        response = request -> calls.incrementAndGet() == 1
                ? new StubResponse(200, "{\"code\":500,\"data\":null}", 0, null)
                : previewResponse();
        assertThat(adapter(3000, 3).quote(quoteRequest(), deadline())).hasSize(1);
        assertThat(calls).hasValue(2);
    }

    @Test
    void timeoutRunsRetriesWithinTotalDeadlineAndBreakerFailsFast() {
        response = request -> new StubResponse(200, previewResponse().body(), 1000, null);
        ShippingProperties properties = shipping(700, 3, 1, 30000);
        CarrierExecutor executor = new CarrierExecutor(properties);
        GhnAdapter adapter = adapter(properties, 200, 0);
        long started = System.nanoTime();
        assertCode(() -> executor.execute("GHN", deadline -> adapter.quote(quoteRequest(), deadline)), "SHIPMENT_CARRIER_UNAVAILABLE");
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(1100);
        assertThat(requests.size()).isBetween(2, 4);
        int before = requests.size();
        started = System.nanoTime();
        assertCode(() -> executor.execute("GHN", deadline -> adapter.quote(quoteRequest(), deadline)), "SHIPMENT_CIRCUIT_OPEN");
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(200);
        assertThat(requests).hasSize(before);
    }

    @Test
    void stalledPreviewBodyIsCancelledAndRetriedWithinDeadline() {
        delayBody = true;
        response = request -> new StubResponse(200, previewResponse().body(), 1000, null);
        GhnAdapter adapter = adapter(shipping(700, 3, 5, 30000), 200, 0);
        long started = System.nanoTime();
        assertCode(() -> adapter.quote(quoteRequest(), ShippingDeadline.after(Duration.ofMillis(700))),
                "SHIPMENT_CARRIER_UNAVAILABLE");
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(1100);
        assertThat(requests.size()).isBetween(2, 4);
    }

    @Test
    void rejectsBadTokenWithoutRetryingOrExposingResponseBody() {
        response = request -> new StubResponse(401, "token-kiem-thu-khong-duoc-lo", 0, null);
        assertCode(() -> adapter(3000, 3).getProvinces(deadline()), "SHIPMENT_CARRIER_CONFIGURATION_ERROR");
        assertThat(requests).hasSize(1);
    }

    @Test
    void addressValidationErrorDoesNotRetry() {
        response = request -> new StubResponse(400,
                "{\"code\":400,\"code_message\":\"USER_ERR_COMMON\",\"data\":null}", 0, null);
        assertCode(() -> adapter(3000, 3).getProvinces(deadline()), "SHIPMENT_INVALID_ADDRESS");
        assertThat(requests).hasSize(1);
    }

    @Test
    void retryAfterBeyondDeadlineStopsWithoutWaitingOrCallingAgain() {
        response = request -> new StubResponse(429, "{}", 0, "60");
        long started = System.nanoTime();
        assertCode(() -> adapter(3000, 3).quote(quoteRequest(), deadline()), "SHIPMENT_CARRIER_UNAVAILABLE");
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(1000);
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "khong-phai-json", "{\"code\":200}", "{\"code\":200,\"data\":null}"})
    void malformedPreviewEnvelopeIsNotRetried(String body) {
        response = request -> new StubResponse(200, body, 0, null);
        assertCode(() -> adapter(3000, 3).quote(quoteRequest(), deadline()), "SHIPMENT_INVALID_CARRIER_RESPONSE");
        assertThat(requests).hasSize(1);
    }

    @Test
    void healthyEightHundredMillisecondCallDoesNotLoseBudgetToUnusedRetries() {
        response = request -> new StubResponse(200, previewResponse().body(), 800, null);
        GhnAdapter adapter = adapter(3000, 3);
        assertThat(adapter.quote(quoteRequest(), deadline())).hasSize(1);
        assertThat(requests).hasSize(1);
    }

    @Test
    void fastTemporaryFailuresRetryOnlyUpToConfiguredLimit() {
        response = request -> new StubResponse(503, "{}", 0, null);
        assertCode(() -> adapter(3000, 3).quote(quoteRequest(), deadline()), "SHIPMENT_CARRIER_UNAVAILABLE");
        assertThat(requests).hasSize(4);
    }

    @Test
    void publicMasterDataWarmsCacheAndQuotesStillCallPreviewEveryTime() {
        AtomicInteger previews = new AtomicInteger();
        response = request -> {
            if (request.path().endsWith("province/all")) {
                return success("[" + locality(1000001, "Hồ Chí Minh", "province", 1, 1) + "]");
            }
            if (request.path().endsWith("ward/all-by-province-id")) {
                return success("[" + locality(1003646, "Phường Sài Gòn", "ward", 1000001, 1) + "]");
            }
            int fee = previews.incrementAndGet() == 1 ? 27500 : 29000;
            return success("{\"order_code\":\"\",\"total_fee\":" + fee
                    + ",\"expected_delivery_time\":\"2026-11-15T16:59:59Z\"}");
        };
        GhnAdapter adapter = adapter(shipping(3000, 3, 5, 30000), 1500, 300000);
        adapter.getProvinces(deadline());
        adapter.getWards("1000001", deadline());
        for (int index = 0; index < 2; index++) {
            adapter.getProvinces(deadline());
            adapter.getWards("1000001", deadline());
        }
        assertThat(adapter.quote(quoteRequest(), deadline()).getFirst().fee()).isEqualByComparingTo("27500");
        assertThat(adapter.quote(quoteRequest(), deadline()).getFirst().fee()).isEqualByComparingTo("29000");
        assertThat(requests).hasSize(4);
        assertThat(requests.stream().filter(request -> request.path().endsWith("province/all"))).hasSize(1);
        assertThat(requests.stream().filter(request -> request.path().endsWith("ward/all-by-province-id"))).hasSize(1);
    }

    @Test
    void cachesValidatedActiveLocalitiesSeparatelyForEachProvince() {
        response = request -> request.query().contains("province_id=1000001")
                ? success("[" + locality(1003646, "Phường A", "ward", 1000001, 1) + ","
                    + locality(1003647, "Phường tỉnh khác", "ward", 1000002, 1) + ","
                    + locality(1003648, "Phường ngưng sử dụng", "ward", 1000001, 2) + "]")
                : success("[" + locality(1003647, "Phường B", "ward", 1000002, 1) + "]");
        GhnAdapter adapter = adapter(shipping(3000, 0, 5, 30000), 1500, 300000);
        List<LocalityResponse> first = adapter.getWards("1000001", deadline());
        assertThat(first).containsExactly(new LocalityResponse("1003646", "Phường A"));
        assertThat(adapter.getWards("1000002", deadline())).containsExactly(new LocalityResponse("1003647", "Phường B"));
        assertThat(adapter.getWards("1000001", deadline())).isEqualTo(first);
        assertThatThrownBy(() -> first.add(new LocalityResponse("1000000", "Không được sửa")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(requests).hasSize(2);
    }

    @Test
    void expiredCacheRefreshesInsteadOfReturningStaleData() throws Exception {
        response = request -> success("[" + locality(1000001, "Tên cũ", "province", 1, 1) + "]");
        GhnAdapter adapter = adapter(shipping(3000, 0, 5, 30000), 1500, 30);
        assertThat(adapter.getProvinces(deadline())).containsExactly(new LocalityResponse("1000001", "Tên cũ"));
        Thread.sleep(60);
        response = request -> success("[" + locality(1000001, "Tên mới", "province", 1, 1) + "]");
        assertThat(adapter.getProvinces(deadline())).containsExactly(new LocalityResponse("1000001", "Tên mới"));
        assertThat(requests).hasSize(2);
    }

    @Test
    void failedRefreshNeverFallsBackToExpiredCache() throws Exception {
        response = request -> success("[" + locality(1000001, "Tên cũ", "province", 1, 1) + "]");
        GhnAdapter adapter = adapter(shipping(3000, 0, 5, 30000), 1500, 30);
        adapter.getProvinces(deadline());
        Thread.sleep(60);
        response = request -> new StubResponse(503, "{}", 0, null);
        assertCode(() -> adapter.getProvinces(deadline()), "SHIPMENT_CARRIER_UNAVAILABLE");
        response = request -> success("[" + locality(1000001, "Tên mới", "province", 1, 1) + "]");
        assertThat(adapter.getProvinces(deadline())).containsExactly(new LocalityResponse("1000001", "Tên mới"));
        assertThat(requests).hasSize(3);
    }

    @Test
    void failedOrMalformedMasterDataIsNotCached() {
        GhnAdapter adapter = adapter(shipping(3000, 0, 5, 30000), 1500, 300000);
        response = request -> new StubResponse(401, "", 0, null);
        assertCode(() -> adapter.getWards("1000001", deadline()), "SHIPMENT_CARRIER_CONFIGURATION_ERROR");
        response = request -> success("null");
        assertCode(() -> adapter.getWards("1000001", deadline()), "SHIPMENT_INVALID_ADDRESS");
        response = request -> success("[{\"_id\":1003646,\"name\":\"Sai schema\"}]");
        assertCode(() -> adapter.getWards("1000001", deadline()), "SHIPMENT_INVALID_CARRIER_RESPONSE");
        response = request -> success("[" + locality(1003646, "Phường A", "ward", 1000001, 1) + "]");
        assertThat(adapter.getWards("1000001", deadline())).containsExactly(new LocalityResponse("1003646", "Phường A"));
        assertThat(adapter.getWards("1000001", deadline())).containsExactly(new LocalityResponse("1003646", "Phường A"));
        assertThat(requests).hasSize(4);
    }

    @Test
    void expiredRequestIsRejectedEvenWhenMasterDataIsCached() {
        response = request -> success("[" + locality(1000001, "Hồ Chí Minh", "province", 1, 1) + "]");
        GhnAdapter adapter = adapter(shipping(3000, 0, 5, 30000), 1500, 300000);
        adapter.getProvinces(deadline());
        ShippingDeadline expired = ShippingDeadline.after(Duration.ofNanos(1));
        assertCode(() -> adapter.getProvinces(expired), "SHIPMENT_CARRIER_UNAVAILABLE");
        assertThat(requests).hasSize(1);
    }

    @Test
    void zeroTtlDisablesMasterDataCache() {
        response = request -> success("[" + locality(1000001, "Hồ Chí Minh", "province", 1, 1) + "]");
        GhnAdapter adapter = adapter(shipping(3000, 0, 5, 30000), 1500, 0);
        adapter.getProvinces(deadline());
        adapter.getProvinces(deadline());
        assertThat(requests).hasSize(2);
    }

    private GhnAdapter adapter(int timeout, int retries) {
        return adapter(shipping(timeout, retries, 5, 30000));
    }

    private GhnAdapter adapter(ShippingProperties shipping) {
        return adapter(shipping, 1500, 0);
    }

    private GhnAdapter adapter(ShippingProperties shipping, int httpTimeoutMs, long cacheTtlMs) {
        return new GhnAdapter(new GhnProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), 12345,
                1, GhnProperties.RequiredNote.KHONGCHOXEMHANG, httpTimeoutMs, cacheTtlMs),
                shipping, new MockEnvironment().withProperty("GHN_TOKEN", "token-kiem-thu"), mapper);
    }

    private ShippingProperties shipping(int timeout, int retries, int threshold, int openMs) {
        return new ShippingProperties(ShippingProperties.CarrierMode.GHN, timeout, retries, 10,
                BigDecimal.valueOf(1_000_000), threshold, openMs, "http://catalog-service", null, null, Map.of());
    }

    private ShippingDeadline deadline() {
        return ShippingDeadline.after(Duration.ofSeconds(3));
    }

    private CarrierQuoteRequest quoteRequest() {
        return new CarrierQuoteRequest(new CarrierAddress("Hồ Chí Minh", "Phường Tân Phú", "Địa chỉ lấy hàng"),
                new CarrierAddress("Hồ Chí Minh", "Phường Sài Gòn", "Địa chỉ giao hàng"),
                new ParcelRequest(1200, 30, 20, 15), BigDecimal.valueOf(250000), "Nguyễn Văn An", "0901234567",
                "3 sản phẩm, 2 mã SKU, 1 kiện hàng");
    }

    private String locality(int id, String name, String type, int parent, int status) {
        return "{\"_id\":" + id + ",\"name\":\"" + name + "\",\"type\":\"" + type
                + "\",\"parent_id\":" + parent + ",\"status\":" + status + "}";
    }

    private void assertCode(Runnable operation, String code) {
        assertThatThrownBy(operation::run).isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).getErrorCode()).isEqualTo(code);
    }

    private StubResponse success(String data) {
        return new StubResponse(200, "{\"code\":200,\"data\":" + data + "}", 0, null);
    }

    private StubResponse previewResponse() {
        return success("{\"order_code\":\"\",\"total_fee\":27500,\"expected_delivery_time\":\"2026-11-15T16:59:59Z\"}");
    }

    private void handle(HttpExchange exchange) throws IOException {
        CapturedRequest request = new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getQuery(), new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                exchange.getRequestHeaders().getFirst("Token"), exchange.getRequestHeaders().getFirst("ShopId"));
        requests.add(request);
        StubResponse stub = response.apply(request);
        try {
            if (!delayBody) {
                Thread.sleep(stub.delayMs());
            }
            if (stub.retryAfter() != null) {
                exchange.getResponseHeaders().set("Retry-After", stub.retryAfter());
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = stub.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(stub.status(), bytes.length == 0 ? -1 : bytes.length);
            if (delayBody && bytes.length > 0) {
                exchange.getResponseBody().write(bytes, 0, 1);
                exchange.getResponseBody().flush();
                Thread.sleep(stub.delayMs());
                exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
                return;
            }
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private record CapturedRequest(String method, String path, String query, String body, String token, String shopId) {
    }

    private record StubResponse(int status, String body, long delayMs, String retryAfter) {
    }
}
