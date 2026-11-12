package com.group5.commerceservice.order.service;

import com.group5.common_core.exception.DomainException;
import com.group5.commerceservice.order.client.InventoryReservationHttpClient;
import com.group5.commerceservice.order.client.OrderRemoteExecutor;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Một lần gọi giữ toàn bộ nhóm đơn; lỗi bất kỳ để transaction Commerce rollback. */
@Component
@RequiredArgsConstructor
public class FeignInventoryClient implements InventoryClient {
    private final InventoryReservationHttpClient http;
    private final OrderRemoteExecutor executor;
    private final ObjectMapper mapper;

    /** Giữ hàng bằng ID đơn thật đã flush; kiểm đủ deadline, kho và danh sách reservation trả về. */
    @Override
    public Reservation reserve(UUID orderGroupId, List<Item> items) {
        if (orderGroupId == null || items == null || items.isEmpty() || items.size() > 100) {
            throw new DomainException(CommerceErrorCode.INVALID_ORDER_REQUEST);
        }
        return executor.execute("inventory", CommerceErrorCode.INVENTORY_UNAVAILABLE, () -> {
            try {
                var response = http.reserve(new InventoryReservationHttpClient.Request(orderGroupId, List.copyOf(items)));
                Reservation result = response == null ? null : response.data();
                if (response == null || response.code() != 0 || result == null || result.warehouseId() == null
                        || result.expiresAt() == null || !result.expiresAt().isAfter(OffsetDateTime.now())
                        || result.reservationIds() == null || result.reservationIds().size() != items.size()
                        || result.reservationIds().stream().anyMatch(java.util.Objects::isNull)
                        || new HashSet<>(result.reservationIds()).size() != items.size()) {
                    throw new DomainException(CommerceErrorCode.INVENTORY_UNAVAILABLE);
                }
                return new Reservation(List.copyOf(result.reservationIds()), result.warehouseId(), result.expiresAt());
            } catch (FeignException error) {
                throw translate(error, items);
            }
        });
    }

    /** Chỉ chuyển lỗi thiếu tồn đã xác minh thành 409; lỗi khả dụng trả 503 để checkout rollback. */
    private DomainException translate(FeignException error, List<Item> items) {
        if (error.status() == 401) return new DomainException(CommerceErrorCode.UNAUTHENTICATED);
        if (error.status() == 403) return new DomainException(CommerceErrorCode.FORBIDDEN);
        if (error.status() == 409) {
            try {
                var problem = mapper.readTree(error.contentUTF8());
                if ("INVENTORY_INSUFFICIENT_STOCK".equals(problem.path("code").asString())
                        && problem.path("skuId").isString() && problem.path("available").isIntegralNumber()
                        && problem.path("available").canConvertToInt()) {
                    UUID sku = UUID.fromString(problem.path("skuId").asString());
                    int available = problem.path("available").intValue();
                    int requested = items.stream().filter(item -> sku.equals(item.skuId()))
                            .mapToInt(Item::quantity).sum();
                    if (available >= 0 && requested > available) {
                        return new DomainException(CommerceErrorCode.INSUFFICIENT_STOCK,
                                "SKU " + sku + " has only " + available + " available",
                                Map.of("skuId", sku, "available", available, "requested", requested));
                    }
                }
            } catch (RuntimeException ignored) {
                // Không trả detail hoặc định dạng lỗi không xác thực từ dịch vụ khác ra client.
            }
        }
        return new DomainException(CommerceErrorCode.INVENTORY_UNAVAILABLE);
    }
}
