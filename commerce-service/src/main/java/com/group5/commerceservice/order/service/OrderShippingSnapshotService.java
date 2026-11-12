package com.group5.commerceservice.order.service;

import com.group5.common_core.exception.DomainException;
import com.group5.commerceservice.order.OrderStatus;
import com.group5.commerceservice.order.dto.response.OrderShippingSnapshotResponse;
import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.entity.OrderItem;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderShippingSnapshotService {
    private final OrderRepository orders;

    public OrderShippingSnapshotService(OrderRepository orders) {
        this.orders = orders;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OrderShippingSnapshotResponse get(UUID orderId) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        if (order.getStatus() != OrderStatus.PAID) {
            throw new DomainException(CommerceErrorCode.ORDER_NOT_PAID);
        }
        if (order.getPaidAt() == null || order.getUserId() == null || order.getSellerId() == null
                || order.getShippingInfo() == null) invalid();
        BigDecimal fee = shippingFee(order.getShippingFee());
        Map<String, Object> info = new LinkedHashMap<>(order.getShippingInfo());
        info.put("recipient", recipient(info));
        // Items are order-line snapshots, never the potentially stale list in shipping_info.
        info.put("items", items(order.getItems()));
        info.putIfAbsent("parcel", null);
        info.putIfAbsent("pickupAddress", null);
        UUID warehouseId = warehouse(info.get("warehouseId"));
        return new OrderShippingSnapshotResponse(order.getId(), order.getUserId(), order.getVersion(),
                order.getStatus().name(), order.getPaidAt(), warehouseId, fee,
                Collections.unmodifiableMap(info), order.getSellerId());
    }

    private static Map<String, Object> recipient(Map<String, Object> info) {
        Map<String, Object> result;
        if (info.containsKey("recipient")) {
            result = new LinkedHashMap<>(object(info.get("recipient")));
        } else {
            Map<String, Object> legacy = object(info.get("shippingAddress"));
            Map<String, Object> address = new LinkedHashMap<>();
            address.put("line1", requiredText(legacy.get("addressLine"), 500));
            for (String field : List.of("wardCode", "districtCode", "provinceCode", "countryCode")) {
                if (legacy.get(field) != null) address.put(field, requiredText(legacy.get(field), 40));
            }
            // Legacy ward/district/city contain names, not verified provider locality codes.
            result = new LinkedHashMap<>();
            result.put("name", requiredText(legacy.get("recipientName"), 150));
            result.put("phone", requiredText(legacy.get("phoneNumber"), 20));
            result.put("address", address);
        }
        requiredText(result.get("name"), 150);
        requiredText(result.get("phone"), 20);
        requiredText(object(result.get("address")).get("line1"), 500);
        return result;
    }

    private static List<Map<String, Object>> items(List<OrderItem> items) {
        if (items == null || items.isEmpty()) invalid();
        return items.stream().map(item -> {
            if (item.getSkuId() == null || item.getQuantity() < 1) invalid();
            return Map.<String, Object>of("skuId", item.getSkuId(),
                    "productName", requiredText(item.getProductName(), 255), "quantity", item.getQuantity());
        }).toList();
    }

    private static BigDecimal shippingFee(BigDecimal fee) {
        if (fee == null || fee.signum() < 0) invalid();
        try {
            BigDecimal value = fee.setScale(2, RoundingMode.UNNECESSARY);
            if (value.precision() > 19) invalid();
            return value;
        } catch (ArithmeticException error) {
            throw new DomainException(CommerceErrorCode.INVALID_SHIPPING_SNAPSHOT);
        }
    }

    private static UUID warehouse(Object value) {
        if (value == null) return null;
        try {
            String text = value.toString();
            UUID id = UUID.fromString(text);
            if (!id.toString().equalsIgnoreCase(text)) invalid();
            return id;
        } catch (IllegalArgumentException error) {
            throw new DomainException(CommerceErrorCode.INVALID_SHIPPING_SNAPSHOT);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> values) || values.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            invalid();
        }
        return (Map<String, Object>) value;
    }

    private static String requiredText(Object value, int max) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > max) invalid();
        return (String) value;
    }

    private static void invalid() {
        throw new DomainException(CommerceErrorCode.INVALID_SHIPPING_SNAPSHOT);
    }
}
