package com.group5.commerceservice.cart.controller;

import com.group5.commerceservice.cart.request.CartItemRequest;
import com.group5.commerceservice.cart.response.CartResponse;
import com.group5.commerceservice.cart.service.CartService;
import com.group5.common_security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/carts")
@RequiredArgsConstructor
@PreAuthorize("hasRole('BUYER')")
public class CartController {

    private final CartService cartService;


    @GetMapping("/me")
    @PreAuthorize("hasAuthority('CART.VIEW') and hasAuthority('CART.CREATE')"
    )
    public ResponseEntity<CartResponse> getMyCart(
            @AuthenticationPrincipal CurrentUser currentUser) {

        return ResponseEntity.ok(
                cartService.getMyCart(currentUser)
        );
    }

    @PostMapping("/me/items")
    @PreAuthorize("hasAuthority('CART.UPDATE')")
    public ResponseEntity<CartResponse> addItem(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody CartItemRequest request) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(cartService.addItem(currentUser, request));
    }
}
