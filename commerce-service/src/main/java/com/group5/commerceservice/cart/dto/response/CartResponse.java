package com.group5.commerceservice.cart.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class CartResponse {

    private UUID id;
    private UUID userId;
    private String status;
    private List<CartItemResponse> items;
    private List<CartIssue> issues;

    public CartResponse(UUID id, UUID userId, String status, List<CartItemResponse> items) {
        this(id, userId, status, items, List.of());
    }
}
