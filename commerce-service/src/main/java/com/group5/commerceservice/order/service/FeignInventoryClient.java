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
import java.util.Optional;
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
                        || new HashSet<>(result.reservationIds()).size() != items.size()
                        || !coversEveryOrder(result.orders(), items)) {
                    throw new DomainException(CommerceErrorCode.INVENTORY_UNAVAILABLE);
                }
                return new Reservation(List.copyOf(result.reservationIds()), result.warehouseId(), result.expiresAt(),
                        result.orders() == null ? null : List.copyOf(result.orders()));
            } catch (FeignException error) {
                throw translate(error, items);
            }
        });
    }

    @Override
    public Optional<OrderShippingService.Pickup> pickup(UUID sellerId, List<UUID> skuIds) {
        if (sellerId == null || skuIds == null || skuIds.isEmpty()) return Optional.empty();
        return executor.execute("inventory", CommerceErrorCode.INVENTORY_UNAVAILABLE, () -> {
            try {
                var response = http.pickup(sellerId, skuIds.stream().distinct().sorted().toList());
                if (response == null || response.code() != 0) throw new DomainException(CommerceErrorCode.INVENTORY_UNAVAILABLE);
                var pickup = response.data();
                if (pickup == null) return Optional.<OrderShippingService.Pickup>empty();
                if (pickup.warehouseId() == null || !sellerId.equals(pickup.sellerId())
                        || pickup.provinceCode() == null || !pickup.provinceCode().matches("[1-9][0-9]{0,8}")
                        || pickup.wardCode() == null || !pickup.wardCode().matches("[1-9][0-9]{0,8}")
                        || pickup.line1() == null || pickup.line1().isBlank() || pickup.line1().length() > 255) {
                    throw new DomainException(CommerceErrorCode.INVENTORY_UNAVAILABLE);
                }
                return Optional.of(new OrderShippingService.Pickup(pickup.warehouseId(), pickup.provinceCode(),
                        pickup.wardCode(), pickup.line1()));
            } catch (FeignException error) {
                if (error.status() == 401) throw new DomainException(CommerceErrorCode.UNAUTHENTICATED);
                if (error.status() == 403) throw new DomainException(CommerceErrorCode.FORBIDDEN);
                throw new DomainException(CommerceErrorCode.INVENTORY_UNAVAILABLE);
            }
        });
    }

    private static boolean coversEveryOrder(List<OrderWarehouse> orders, List<Item> items) {
        if (orders == null) return true;
        if (orders.stream().anyMatch(order -> order == null || order.orderId() == null || order.warehouseId() == null)) {
            return false;
        }
        var expected = new HashSet<UUID>();
        items.forEach(item -> expected.add(item.orderId()));
        var returned = new HashSet<UUID>();
        orders.forEach(order -> returned.add(order.orderId()));
        return returned.size() == orders.size() && returned.equals(expected);
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
