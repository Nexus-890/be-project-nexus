package com.group5.commerceservice.order.controller;

import com.group5.commerceservice.order.OrderStatus;
import com.group5.commerceservice.order.service.OrderService;
import com.group5.commerceservice.order.dto.request.ChangeOrderStatusRequest;
import com.group5.commerceservice.order.dto.request.CreateOrderRequest;
import com.group5.commerceservice.order.dto.response.OrderGroupResponse;
import com.group5.commerceservice.order.dto.response.OrderResponse;
import com.group5.commerceservice.order.dto.response.OrderStatusHistoryResponse;
import com.group5.common_security.CurrentUser;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/api/v1/orders")
    @PreAuthorize("hasAuthority('CHECKOUT.START') and hasAuthority('ORDER.CREATE')")
    public ResponseEntity<ApiResponse<OrderGroupResponse>> create(CurrentUser user,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        OrderService.CreationResult result = orderService.create(user, idempotencyKey, request);
        return ResponseEntity.status(result.created() ? 201 : 200).body(ApiResponse.of(result.response()));
    }

    @GetMapping("/api/v1/orders/{id}")
    @PreAuthorize("hasAuthority('ORDER.VIEW')")
    public ApiResponse<OrderResponse> getOrder(CurrentUser user, @PathVariable UUID id) {
        return ApiResponse.of(orderService.getOrder(user, id));
    }

    @GetMapping("/api/v1/orders")
    @PreAuthorize("hasAuthority('ORDER.LIST')")
    public ApiResponse<List<OrderResponse>> listOwn(CurrentUser user,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<OrderResponse> orders = orderService.listOwn(user, status, page, size);
        return ApiResponse.page(orders.getContent(), new PageInfo(
                orders.getNumber(), orders.getSize(), orders.getTotalElements(), orders.getTotalPages()));
    }

    @PatchMapping("/api/v1/orders/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<OrderResponse> changeStatus(CurrentUser user, @PathVariable UUID id,
            @Valid @RequestBody ChangeOrderStatusRequest request) {
        return ApiResponse.of(orderService.changeStatus(user, id, request));
    }

    @GetMapping("/api/v1/orders/{id}/history")
    @PreAuthorize("hasAuthority('ORDER.VIEW')")
    public ApiResponse<List<OrderStatusHistoryResponse>> statusHistory(CurrentUser user, @PathVariable UUID id) {
        return ApiResponse.list(orderService.statusHistory(user, id));
    }

    @GetMapping("/internal/order-references/products/{productId}")
    @PreAuthorize("hasAuthority('PRODUCT.DELETE')")
    public ApiResponse<Map<String, Boolean>> hasProductReferences(@PathVariable UUID productId) {
        return ApiResponse.of(Map.of("hasOrders", orderService.hasProductReferences(productId)));
    }
}
