package com.group5.commerceservice.order.controller;

import com.group5.commerceservice.order.service.OrderService;
import com.group5.commerceservice.order.dto.request.ChangeOrderStatusRequest;
import com.group5.commerceservice.order.dto.request.CreateOrderRequest;
import com.group5.commerceservice.order.dto.response.OrderGroupResponse;
import com.group5.commerceservice.order.dto.response.OrderResponse;
import com.group5.commerceservice.order.dto.response.OrderStatusHistoryResponse;
import com.group5.common_security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
    public ResponseEntity<OrderGroupResponse> create(CurrentUser user,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        OrderService.CreationResult result = orderService.create(user, idempotencyKey, request);
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.response());
    }

    @PatchMapping("/api/v1/orders/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public OrderResponse changeStatus(CurrentUser user, @PathVariable UUID id,
            @Valid @RequestBody ChangeOrderStatusRequest request) {
        return orderService.changeStatus(user, id, request);
    }

    @GetMapping("/api/v1/orders/{id}/history")
    @PreAuthorize("hasAuthority('ORDER.VIEW')")
    public List<OrderStatusHistoryResponse> statusHistory(CurrentUser user, @PathVariable UUID id) {
        return orderService.statusHistory(user, id);
    }

    @GetMapping("/internal/order-references/products/{productId}")
    @PreAuthorize("hasAuthority('PRODUCT.DELETE')")
    public Map<String, Boolean> hasProductReferences(@PathVariable UUID productId) {
        return Map.of("hasOrders", orderService.hasProductReferences(productId));
    }
}
