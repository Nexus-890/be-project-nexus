package com.group5.commerceservice.order.controller;

import com.group5.commerceservice.order.service.OrderService;
import com.group5.commerceservice.order.dto.request.CreateOrderRequest;
import com.group5.commerceservice.order.dto.response.OrderGroupResponse;
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

    @GetMapping("/internal/order-references/products/{productId}")
    @PreAuthorize("hasAuthority('PRODUCT.DELETE')")
    public Map<String, Boolean> hasProductReferences(@PathVariable UUID productId) {
        return Map.of("hasOrders", orderService.hasProductReferences(productId));
    }
}
