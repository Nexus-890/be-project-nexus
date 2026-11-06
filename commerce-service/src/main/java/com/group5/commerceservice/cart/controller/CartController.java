package com.group5.commerceservice.cart.controller;

import com.group5.commerceservice.cart.dto.request.CartItemRequest;
import com.group5.commerceservice.cart.dto.request.UpdateCartItemRequest;
import com.group5.commerceservice.cart.dto.response.CartResponse;
import com.group5.commerceservice.cart.dto.response.CartPricingResponse;
import com.group5.commerceservice.cart.dto.response.CartValidationResponse;
import com.group5.commerceservice.cart.service.CartPricingService;
import com.group5.commerceservice.cart.service.CartService;
import com.group5.common_security.CurrentUser;
import com.group5.common_core.model.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/carts")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;
    private final CartPricingService cartPricingService;

    @GetMapping("/me/pricing")
    @PreAuthorize("hasAuthority('CART.VIEW')")
    public ApiResponse<CartPricingResponse> pricing(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) String promoCode) {
        return ApiResponse.of(cartPricingService.pricing(currentUser, promoCode));
    }

    @PostMapping("/me/validate")
    @PreAuthorize("hasAuthority('CART.VIEW')")
    public ApiResponse<CartValidationResponse> validate(@AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.of(cartPricingService.validate(currentUser));
    }

    // Gateway yêu cầu CART.VIEW (primary privilege).
    // Service yêu cầu thêm CART.CREATE vì GET /me có thể tạo cart lần đầu.
    // Ref: docs/api/gateway-permissions.md — CRT-01
    @GetMapping("/me")
    @PreAuthorize("hasAuthority('CART.VIEW') and hasAuthority('CART.CREATE')")
    public ResponseEntity<ApiResponse<CartResponse>> getMyCart(
            @AuthenticationPrincipal CurrentUser currentUser) {
        return ResponseEntity.ok(ApiResponse.of(cartService.getMyCart(currentUser)));
    }

    // Gateway yêu cầu CART.UPDATE (primary privilege).
    // Ref: docs/api/gateway-permissions.md — CRT-02
    @PostMapping("/me/items")
    @PreAuthorize("hasAuthority('CART.UPDATE')")
    public ResponseEntity<ApiResponse<CartResponse>> addItem(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody CartItemRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.of(cartService.addItem(currentUser, request)));
    }

    @PutMapping("/me/items/{id}")
    @PreAuthorize("hasAuthority('CART.UPDATE')")
    public ResponseEntity<ApiResponse<CartResponse>> updateItem(
            @Valid @RequestBody UpdateCartItemRequest request,
            CurrentUser currentUser,
            @PathVariable("id") UUID id) {
        return ResponseEntity.ok(ApiResponse.of(cartService.updateCartItem(currentUser, id, request)));
    }

    @DeleteMapping("/me/items/{id}")
    @PreAuthorize("hasAuthority('CART.REMOVE_ITEM')")
    public ResponseEntity<Void> removeItem(
            CurrentUser currentUser,
            @PathVariable("id") UUID id) {
        cartService.removeCartItem(currentUser, id);
        return ResponseEntity.noContent().build();
    }
}
