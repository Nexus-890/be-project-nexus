package com.group5.commerceservice.order.service;

import com.group5.common_core.exception.DomainException;
import com.group5.commerceservice.cart.dto.CatalogSkuResponse;
import com.group5.commerceservice.order.client.OrderRemoteExecutor;
import com.group5.commerceservice.order.client.ShippingQuoteHttpClient;
import com.group5.commerceservice.order.dto.request.CreateOrderRequest.ShippingAddress;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Đóng một kiện mỗi seller từ số đo SKU tin cậy, rồi lấy phí trực tiếp từ shipment-service. */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderShippingService {
    private final ShippingQuoteHttpClient http;
    private final OrderRemoteExecutor executor;
    private final ObjectMapper mapper;
    private static final Comparator<ShippingQuoteHttpClient.Response> BY_PRICE = Comparator
            .comparing(ShippingQuoteHttpClient.Response::fee)
            .thenComparing(ShippingQuoteHttpClient.Response::estimatedDeliveryAt);

    public record Line(UUID skuId, int quantity, CatalogSkuResponse sku) { }
    public record Parcel(int weightGrams, int lengthCm, int widthCm, int heightCm) { }
    public record Pickup(UUID warehouseId, String provinceCode, String wardCode, String line1) {
        public Map<String, Object> snapshot() {
            return Map.of("line1", line1, "provinceCode", provinceCode, "wardCode", wardCode);
        }
    }
    public record Quote(BigDecimal fee, Parcel parcel, String carrier, String serviceType,
                        OffsetDateTime estimatedDeliveryAt, Pickup pickup) {
        public Quote(BigDecimal fee, Parcel parcel, String carrier, String serviceType,
                     OffsetDateTime estimatedDeliveryAt) {
            this(fee, parcel, carrier, serviceType, estimatedDeliveryAt, null);
        }
    }
    public record Option(String carrier, String carrierName, BigDecimal fee, OffsetDateTime estimatedDeliveryAt,
                         boolean recommended) { }
    public record Offer(Parcel parcel, String serviceType, List<Option> options, Pickup pickup) {
        public Offer(Parcel parcel, String serviceType, List<Option> options) {
            this(parcel, serviceType, options, null);
        }

        public Quote select(String carrier) {
            Option chosen = carrier == null ? recommended()
                    : options.stream().filter(option -> option.carrier().equals(carrier)).findFirst()
                    .orElseThrow(() -> new DomainException(CommerceErrorCode.SHIPPING_CARRIER_UNAVAILABLE));
            return new Quote(chosen.fee(), parcel, chosen.carrier(), serviceType, chosen.estimatedDeliveryAt(), pickup);
        }

        public Option recommended() {
            return options.stream().filter(Option::recommended).findFirst()
                    .orElseGet(() -> options.stream().min(Comparator.comparing(Option::fee)
                            .thenComparing(Option::carrier).thenComparing(Option::estimatedDeliveryAt)).orElseThrow());
        }
    }

    /** Chốt kiện từ Catalog, gửi địa chỉ hai cấp và nhận giá/loại dịch vụ đã kiểm tra từ Shipping. */
    public Quote quote(ShippingAddress address, List<Line> lines) {
        return quote(address, lines, null);
    }

    public Quote quote(ShippingAddress address, List<Line> lines, UUID warehouseId) {
        return offer(address, lines, warehouseId, null, null).select(null);
    }

    public Offer offer(ShippingAddress address, List<Line> lines) {
        return offer(address, lines, null, null, null);
    }

    public Offer offer(ShippingAddress address, List<Line> lines, Pickup pickup) {
        return offer(address, lines, pickup == null ? null : pickup.warehouseId(), null, pickup);
    }

    /** Shipment trả phí carrier gốc; Commerce dùng giá đấu thắng để xét miễn phí. */
    public Quote quoteAuction(ShippingAddress address, List<Line> lines, UUID warehouseId) {
        return offer(address, lines, warehouseId, false, null).select(null);
    }

    public Quote quoteAuction(ShippingAddress address, List<Line> lines, UUID warehouseId, Pickup pickup) {
        return offer(address, lines, warehouseId, false, pickup).select(null);
    }

    private Offer offer(ShippingAddress address, List<Line> lines, UUID warehouseId, Boolean applyFreeShipping,
                        Pickup pickup) {
        Parcel parcel = pack(lines);
        var destination = new ShippingQuoteHttpClient.Address(address.provinceCode(), address.wardCode(),
                address.addressLine(), address.recipientName(), address.phoneNumber());
        // Trước reserve chưa có warehouseId. Shipment dùng default đã cấu hình cùng kho Inventory;
        // Commerce không giữ mã kho và chỉ lưu UUID thật khi reservation trả về.
        var request = new ShippingQuoteHttpClient.Request(destination,
                lines.stream().map(line -> new ShippingQuoteHttpClient.Item(line.skuId(), line.quantity())).toList(),
                parcel, warehouseId, applyFreeShipping, pickup == null ? null
                : new ShippingQuoteHttpClient.PickupAddress(pickup.provinceCode(), pickup.wardCode(), pickup.line1()));
        return executor.execute("shipping", CommerceErrorCode.SHIPPING_UNAVAILABLE, () -> {
            try {
                var response = http.quote(request);
                var quotes = response == null ? null : response.data();
                String service = parcel.weightGrams() < 20000 ? "STANDARD" : "HEAVY";
                if (response == null || response.code() != 0 || quotes == null || quotes.isEmpty()
                        || quotes.stream().anyMatch(quote -> quote == null || quote.carrier() == null
                        || quote.carrier().isBlank() || !service.equals(quote.serviceType()) || quote.fee() == null
                        || quote.fee().signum() < 0 || quote.fee().stripTrailingZeros().scale() > 0
                        || quote.fee().precision() - quote.fee().scale() > 17 || quote.estimatedDeliveryAt() == null)) {
                    throw new DomainException(CommerceErrorCode.SHIPPING_UNAVAILABLE);
                }
                // Chọn giá thấp nhất, dùng carrier/ETA để thứ tự lựa chọn luôn xác định khi đồng giá.
                Map<String, ShippingQuoteHttpClient.Response> cheapest = new LinkedHashMap<>();
                for (var quote : quotes) {
                    cheapest.merge(quote.carrier(), quote, (kept, next) -> BY_PRICE.compare(next, kept) < 0 ? next : kept);
                }
                return new Offer(parcel, service, cheapest.values().stream().map(quote -> new Option(quote.carrier(),
                        quote.carrierName() == null || quote.carrierName().isBlank() ? quote.carrier() : quote.carrierName(),
                        quote.fee().setScale(2), quote.estimatedDeliveryAt(), quotes.stream().anyMatch(row ->
                        row.carrier().equals(quote.carrier()) && Boolean.TRUE.equals(row.recommended())))).toList(), pickup);
            } catch (FeignException error) {
                // Chỉ ghi trạng thái/lớp lỗi, không ghi token, địa chỉ hay body của người nhận.
                log.warn("Shipping quote call failed: status={}, failure={}, code={}", error.status(),
                        error.getClass().getSimpleName(), problemCode(error));
                throw translate(error);
            }
        });
    }

    /** Quy tắc đóng gói xác định: giữ chiều dài/rộng lớn nhất, xếp chồng chiều cao, cộng đủ cân nặng. */
    public Parcel pack(List<Line> lines) {
        if (lines == null || lines.isEmpty() || lines.size() > 100) {
            throw new DomainException(CommerceErrorCode.INVALID_PARCEL);
        }
        long weight = 0;
        long height = 0;
        int length = 0;
        int width = 0;
        for (Line line : lines) {
            var unit = line.sku() == null ? null : line.sku().getShippingParcel();
            if (unit == null) {
                throw new DomainException(CommerceErrorCode.SHIPPING_METADATA_REQUIRED,
                        CommerceErrorCode.SHIPPING_METADATA_REQUIRED.message(), Map.of("skuId", line.skuId()));
            }
            if (line.quantity() < 1 || line.quantity() > 999 || !inRange(unit.weightGrams(), 50000)
                    || !inRange(unit.lengthCm(), 200) || !inRange(unit.widthCm(), 200) || !inRange(unit.heightCm(), 200)) {
                throw new DomainException(CommerceErrorCode.INVALID_PARCEL);
            }
            weight += (long) unit.weightGrams() * line.quantity();
            height += (long) unit.heightCm() * line.quantity();
            length = Math.max(length, unit.lengthCm());
            width = Math.max(width, unit.widthCm());
            if (weight > 50000 || height > 200) throw new DomainException(CommerceErrorCode.INVALID_PARCEL);
        }
        return new Parcel((int) weight, length, width, (int) height);
    }

    private static boolean inRange(Integer number, int maximum) {
        return number != null && number > 0 && number <= maximum;
    }

    private DomainException translate(FeignException error) {
        if (error.status() == 401) return new DomainException(CommerceErrorCode.UNAUTHENTICATED);
        if (error.status() == 403) return new DomainException(CommerceErrorCode.FORBIDDEN);
        try {
            var problem = mapper.readTree(error.contentUTF8());
            if (error.status() == 400 && "SHIPMENT_INVALID_ADDRESS".equals(problem.path("code").asString())) {
                return new DomainException(CommerceErrorCode.INVALID_SHIPPING_ADDRESS);
            }
            if (error.status() == 422 && "SHIPMENT_NO_SERVICE".equals(problem.path("code").asString())) {
                return new DomainException(CommerceErrorCode.SHIPPING_NOT_AVAILABLE);
            }
        } catch (RuntimeException ignored) {
            // Các body lỗi không hợp lệ được coi là lỗi khả dụng, không dùng detail của upstream.
        }
        return new DomainException(CommerceErrorCode.SHIPPING_UNAVAILABLE);
    }

    /** Chỉ lấy mã lỗi ký hiệu phục vụ chẩn đoán, không đưa body upstream vào log. */
    private String problemCode(FeignException error) {
        try {
            String code = mapper.readTree(error.contentUTF8()).path("code").asString();
            return code != null && code.matches("[A-Z_]{1,80}") ? code : "UNKNOWN";
        } catch (RuntimeException invalid) {
            return "UNKNOWN";
        }
    }
}
