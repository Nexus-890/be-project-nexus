package com.group5.commerceservice.cart.service;

import com.group5.commerceservice.cart.CartStatus;
import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.dto.CatalogSkuResponse;
import com.group5.commerceservice.cart.dto.request.CartItemRequest;
import com.group5.commerceservice.cart.dto.request.UpdateCartItemRequest;
import com.group5.commerceservice.cart.dto.response.CartIssue;
import com.group5.commerceservice.cart.dto.response.CartItemResponse;
import com.group5.commerceservice.cart.dto.response.CartResponse;
import com.group5.commerceservice.cart.dto.response.GuestCartResponse;
import com.group5.commerceservice.cart.entity.Cart;
import com.group5.commerceservice.cart.entity.CartItem;
import com.group5.commerceservice.cart.repository.CartItemRepository;
import com.group5.commerceservice.cart.repository.CartRepository;
import com.group5.commerceservice.shared.config.CartProperties;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class CartService {
    private static final int MAX_QUANTITY = 999;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final CatalogClient catalogClient;
    private final CartProperties properties;
    private final GuestCartTokenService guestTokens;
    private final CartPricingService pricing;

    public GuestCartResponse createGuestCart() {
        requireGuestEnabled();
        Cart cart = new Cart();
        cart.setGuestToken(guestTokens.create());
        cartRepository.saveAndFlush(cart);
        return new GuestCartResponse(cart.getGuestToken(), toResponse(cart));
    }

    public CartResponse getMyCart(CurrentUser user) {
        return getMyCart(user, null);
    }

    public CartResponse getMyCart(CurrentUser user, String guestToken) {
        return toResponse(resolveCart(user, guestToken, true));
    }

    public CartResponse addItem(CurrentUser user, CartItemRequest request) {
        return addItem(user, null, request);
    }

    public CartResponse addItem(CurrentUser user, String guestToken, CartItemRequest request) {
        if (request == null || request.getSkuId() == null) throw new DomainException(CommerceErrorCode.INVALID_QUANTITY);
        validateQuantity(request.getQuantity());
        if (user == null) {
            requireGuestEnabled();
            guestTokens.validate(guestToken);
        } else {
            requireUserId(user);
        }
        CatalogSkuResponse sku = readSku(request.getSkuId());
        Cart cart = resolveCart(user, guestToken, true);
        CartItem item = cartItemRepository.findByCart_Id(cart.getId()).stream()
                .filter(line -> line.getSkuId().equals(sku.getId())).findFirst().orElse(null);
        long quantity = (item == null ? 0L : item.getQuantity().longValue()) + request.getQuantity();
        if (quantity > MAX_QUANTITY) throw new DomainException(CommerceErrorCode.INVALID_QUANTITY);
        if (item == null) {
            item = new CartItem();
            item.setCart(cart);
            item.setSkuId(sku.getId());
            item.setProductId(sku.getProductId());
        }
        item.setQuantity((int) quantity);
        item.setUnitPrice(sku.getPrice());
        cartItemRepository.save(item);
        touch(cart);
        return toResponse(cart);
    }

    public CartResponse updateCartItem(CurrentUser user, UUID itemId, UpdateCartItemRequest request) {
        return updateCartItem(user, null, itemId, request);
    }

    public CartResponse updateCartItem(CurrentUser user, String guestToken, UUID itemId, UpdateCartItemRequest request) {
        validateQuantity(request == null ? null : request.getQuantity());
        Cart cart = resolveCart(user, guestToken, false);
        ownedItem(cart, itemId).setQuantity(request.getQuantity());
        touch(cart);
        return toResponse(cart);
    }

    public void removeCartItem(CurrentUser user, UUID itemId) {
        removeCartItem(user, null, itemId);
    }

    public void removeCartItem(CurrentUser user, String guestToken, UUID itemId) {
        Cart cart = resolveCart(user, guestToken, false);
        cartItemRepository.delete(ownedItem(cart, itemId));
        touch(cart);
    }

    public CartResponse mergeGuestCart(CurrentUser user, String guestToken) {
        UUID userId = requireUserId(user);
        if (!user.hasRole("BUYER") || !user.hasPrivilege("CART.UPDATE")) throw new DomainException(CommerceErrorCode.FORBIDDEN);
        requireGuestEnabled();
        //kiểm tra config cho merge hông
        if (!properties.isMergeOnLogin()) throw new DomainException(CommerceErrorCode.CART_MERGE_DISABLED);
        //lấy ra 2 cái cart để gộp
        Cart guest = guestCart(guestToken);
        Cart target = getOrCreateActiveCart(userId);
        //đưa các dòng trong giỏ ( buyer đã login ) vào Map với key là skuId -> tiện cho việc tìm nhanh sku đã tồn tại khi duyệt các dòng của giỏ guest
        var targetLines = new HashMap<UUID, CartItem>();
        cartItemRepository.findByCart_Id(target.getId()).forEach(line -> targetLines.put(line.getSkuId(), line));//ném cartItem vô Map
        List<CartIssue> issues = new ArrayList<>();
        for (CartItem source : cartItemRepository.findByCart_Id(guest.getId())) {//source là cartItem của bên guest nhe
            CartItem line = targetLines.get(source.getSkuId());//kiểm tra xem trong cartBuyer có item giống ở cartGuest hông
            long quantity = source.getQuantity().longValue() + (line == null ? 0L : line.getQuantity().longValue());
            if (line == null) {
                line = new CartItem();
                line.setCart(target);
                line.setProductId(source.getProductId());
                line.setSkuId(source.getSkuId());
                line.setUnitPrice(source.getUnitPrice());
            }
            line.setQuantity((int) Math.min(MAX_QUANTITY, quantity));
            line = cartItemRepository.saveAndFlush(line);
            if (quantity > MAX_QUANTITY) issues.add(new CartIssue(line.getId(), line.getSkuId(), CartIssue.Code.QUANTITY_CAPPED));
        }
        touch(target);
        cartItemRepository.flush();
        issues.addAll(pricing.mergeIssues(user));
        guest.setStatus(CartStatus.MERGED);
        touch(guest);
        CartResponse result = toResponse(target);
        return new CartResponse(result.getId(), result.getUserId(), result.getStatus(), result.getItems(), List.copyOf(issues));
    }

    private Cart resolveCart(CurrentUser user, String guestToken, boolean create) {
        if (user == null) {
            requireGuestEnabled();
            return guestCart(guestToken);
        }
        UUID userId = requireUserId(user);
        return create ? getOrCreateActiveCart(userId) : cartRepository.findForUpdateByUserIdAndStatus(userId, CartStatus.ACTIVE)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.CART_NOT_FOUND));
    }

    private Cart guestCart(String token) {
        guestTokens.validate(token);
        Cart cart = cartRepository.findForUpdateByGuestTokenAndUserIdIsNullAndStatus(token, CartStatus.ACTIVE)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.GUEST_CART_NOT_FOUND));
        if (properties.getExpirationDays() < 1) throw new DomainException(CommerceErrorCode.INVALID_CONFIGURATION);
        if (!cart.getUpdatedAt().plusDays(properties.getExpirationDays()).isAfter(OffsetDateTime.now())) {
            throw new DomainException(CommerceErrorCode.GUEST_CART_NOT_FOUND);
        }
        return cart;
    }

    private CartItem ownedItem(Cart cart, UUID itemId) {
        return cartItemRepository.findByCart_Id(cart.getId()).stream().filter(line -> line.getId().equals(itemId))
                .findFirst().orElseThrow(() -> new DomainException(CommerceErrorCode.CART_NOT_FOUND));
    }

    private Cart getOrCreateActiveCart(UUID userId) {
        //
        for (int attempt = 0; attempt < 2; attempt++) {
            cartRepository.createActiveCartIfAbsent(UUID.randomUUID(), userId, userId);
            var cart = cartRepository.findForUpdateByUserIdAndStatus(userId, CartStatus.ACTIVE);
            if (cart.isPresent()) return cart.get();
        }
        throw new DomainException(CommerceErrorCode.DB_ERROR);
    }

    private CatalogSkuResponse readSku(UUID skuId) {
        try {
            var response = catalogClient.getSkuById(skuId);
            CatalogSkuResponse sku = response == null ? null : response.data();
            if (sku == null || !skuId.equals(sku.getId()) || sku.getProductId() == null
                    || sku.getPrice() == null || sku.getPrice().signum() < 0) {
                throw new DomainException(CommerceErrorCode.INVALID_CATALOG_RESPONSE);
            }
            return sku;
        } catch (FeignException.NotFound exception) {
            throw new DomainException(CommerceErrorCode.SKU_NOT_FOUND);
        } catch (FeignException exception) {
            throw new DomainException(CommerceErrorCode.CATALOG_UNAVAILABLE);
        }
    }

    private void requireGuestEnabled() {
        if (!properties.isAllowGuestCart()) throw new DomainException(CommerceErrorCode.GUEST_CART_DISABLED);
    }

    private UUID requireUserId(CurrentUser user) {
        if (user == null || user.id() == null) throw new DomainException(CommerceErrorCode.UNAUTHENTICATED);
        return user.id();
    }

    private void validateQuantity(Integer quantity) {
        if (quantity == null || quantity < 1 || quantity > MAX_QUANTITY) throw new DomainException(CommerceErrorCode.INVALID_QUANTITY);
    }

    private void touch(Cart cart) {
        cart.setUpdatedAt(OffsetDateTime.now());
    }

    private CartResponse toResponse(Cart cart) {
        List<CartItemResponse> items = cartItemRepository.findByCart_Id(cart.getId()).stream()
                .map(item -> new CartItemResponse(item.getId(), item.getProductId(), item.getSkuId(),
                        item.getQuantity(), item.getUnitPrice())).toList();
        return new CartResponse(cart.getId(), cart.getUserId(), cart.getStatus().name(), items);
    }
}
