package com.group5.commerceservice.cart.service;

import com.group5.common_core.constant.ErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.dto.CatalogSkuResponse;
import com.group5.commerceservice.cart.entity.Cart;
import com.group5.commerceservice.cart.entity.CartItem;
import com.group5.commerceservice.cart.repository.CartItemRepository;
import com.group5.commerceservice.cart.repository.CartRepository;
import com.group5.commerceservice.cart.request.CartItemRequest;
import com.group5.commerceservice.cart.response.CartItemResponse;
import com.group5.commerceservice.cart.response.CartResponse;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CartService {

    private static final String ACTIVE = "ACTIVE";

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final CatalogClient catalogClient;

    @Transactional
    public CartResponse getMyCart(CurrentUser currentUser) {
        UUID userId = requireUserId(currentUser);
        Cart cart = getOrCreateActiveCart(userId);

        return toResponse(cart);
    }

    @Transactional
    public CartResponse addItem(
            CurrentUser currentUser,
            CartItemRequest request
    ) {
        UUID userId = requireUserId(currentUser);

        if (request == null
                || request.getSkuId() == null
                || request.getQuantity() == null
                || request.getQuantity() <= 0) {
            throw new DomainException(ErrorCode.INVALID_QUANTITY);
        }

        CatalogSkuResponse sku;

        try {
            sku = catalogClient.getSkuById(request.getSkuId());
        } catch (FeignException.NotFound ex) {
            throw new DomainException(ErrorCode.SKU_NOT_FOUND);
        }

        if (sku == null
                || sku.getId() == null
                || sku.getProductId() == null
                || sku.getPrice() == null
                || sku.getPrice().signum() < 0) {
            throw new DomainException(ErrorCode.SKU_NOT_FOUND);
        }

        Cart cart = getOrCreateActiveCart(userId);

        if (cartRepository.touchActiveCart(cart.getId()) == 0) {
            // Cart có thể vừa bị job chuyển sang EXPIRED.
            // Lấy hoặc tạo lại cart ACTIVE.
            cart = getOrCreateActiveCart(userId);

            if (cartRepository.touchActiveCart(cart.getId()) == 0) {
                throw new DomainException(ErrorCode.DB_ERROR);
            }
        }

        cartItemRepository.addOrIncreaseQuantity(
                UUID.randomUUID(),
                cart.getId(),
                sku.getProductId(),
                sku.getId(),
                request.getQuantity(),
                sku.getPrice()
        );

        return toResponse(cart);
    }

    private UUID requireUserId(CurrentUser currentUser) {
        if (currentUser == null || currentUser.id() == null) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED);
        }

        return currentUser.id();
    }

    private Cart getOrCreateActiveCart(UUID userId) {
        return cartRepository.findByUserIdAndStatus(userId, ACTIVE)
                .orElseGet(() -> {
                    cartRepository.createActiveCartIfAbsent(
                            UUID.randomUUID(), userId);

                    return cartRepository.findByUserIdAndStatus(userId, ACTIVE)
                            .orElseThrow(
                                    () -> new DomainException(ErrorCode.DB_ERROR));
                });
    }

    private CartResponse toResponse(Cart cart) {
        List<CartItemResponse> items =
                cartItemRepository.findByCart_Id(cart.getId())
                        .stream()
                        .map(this::toItemResponse)
                        .toList();

        return new CartResponse(
                cart.getId(),
                cart.getUserId(),
                cart.getStatus(),
                items
        );
    }

    private CartItemResponse toItemResponse(CartItem item) {
        return new CartItemResponse(
                item.getId(),
                item.getProductId(),
                item.getSkuId(),
                item.getQuantity(),
                item.getPrice()
        );
    }
}
