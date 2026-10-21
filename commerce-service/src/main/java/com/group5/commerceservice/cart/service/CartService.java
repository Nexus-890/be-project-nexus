package com.group5.commerceservice.cart.service;

import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.dto.CatalogSkuResponse;
import com.group5.commerceservice.cart.dto.request.UpdateCartItemRequest;
import com.group5.commerceservice.cart.entity.Cart;
import com.group5.commerceservice.cart.entity.CartItem;
import com.group5.commerceservice.cart.repository.CartItemRepository;
import com.group5.commerceservice.cart.repository.CartRepository;
import com.group5.commerceservice.cart.dto.request.CartItemRequest;
import com.group5.commerceservice.cart.dto.response.CartItemResponse;
import com.group5.commerceservice.cart.dto.response.CartResponse;
import com.group5.common_core.constant.ErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Slf4j
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
    public CartResponse addItem(CurrentUser currentUser, CartItemRequest request) {
        UUID userId = requireUserId(currentUser);

        // Bean validation (@Positive, @NotNull) đã chặn quantity <= 0 và null ở tầng controller.
        // Guard này là lớp bảo vệ thứ hai nếu service được gọi trực tiếp.
        if (request == null
                || request.getSkuId() == null
                || request.getQuantity() == null
                || request.getQuantity() <= 0) {
            throw new DomainException(ErrorCode.INVALID_QUANTITY);
        }

        // Lấy thông tin SKU từ catalog-service qua Feign.
        // FeignException.NotFound → 404; các lỗi khác để GlobalExceptionHandler xử lý.
        CatalogSkuResponse sku;
        try {
            sku = catalogClient.getSkuById(request.getSkuId());
        } catch (FeignException.NotFound ex) {
            throw new DomainException(ErrorCode.SKU_NOT_FOUND);
        }

        // Validate response SKU: phải có id, productId, và price không âm.
        if (sku == null
                || sku.getId() == null
                || sku.getProductId() == null
                || sku.getPrice() == null
                || sku.getPrice().signum() < 0) {
            throw new DomainException(ErrorCode.SKU_NOT_FOUND);
        }

        Cart cart = getOrCreateActiveCart(userId);

        // touchActiveCart cập nhật updated_at và trả về số row ảnh hưởng.
        // Nếu trả về 0, cart vừa bị expire trong khoảng thời gian ngắn → tạo lại.
        if (cartRepository.touchActiveCart(cart.getId()) == 0) {
            cart = getOrCreateActiveCart(userId);
            if (cartRepository.touchActiveCart(cart.getId()) == 0) {
                log.error(
                        "Failed to update active cart after retry. userId={}, cartId={}",
                        userId,
                        cart.getId()
                );
                throw new DomainException(ErrorCode.DB_ERROR);
            }
        }

        // Upsert: nếu SKU đã có trong cart → cộng quantity; nếu chưa → INSERT.
        // price luôn được cập nhật theo giá SKU hiện tại tại thời điểm thêm.
        cartItemRepository.addOrIncreaseQuantity(
                UUID.randomUUID(),
                cart.getId(),
                sku.getProductId(),
                sku.getId(),
                request.getQuantity(),
                sku.getPrice()
        );

        // clearAutomatically = true trên addOrIncreaseQuantity đảm bảo
        // findByCart_Id dưới đây đọc dữ liệu mới nhất từ DB, không phải cache cũ.
        return toResponse(cart);
    }

    @Transactional
    public CartResponse updateCartItem(
            CurrentUser currentUser, UUID itemId, UpdateCartItemRequest request) {
        //kiểm tra quyền tránh user này cập nhật cart user khác
        UUID userId = requireUserId(currentUser);

        //số lượng null hoặc <= 0 thì ném lỗi 400
        if (request == null || request.getQuantity() == null || request.getQuantity() <= 0) {
            throw new DomainException(ErrorCode.INVALID_QUANTITY);
        }
        //tìm cart, nếu k có hoặc k active thì 404 not found
        Cart cart = getActiveCartOrThrow(userId);

        if (cartRepository.touchActiveCart(cart.getId()) == 0) {
            throw new DomainException(ErrorCode.NOT_FOUND);
        }
        //tìm item, kiểm tra xem giỏ đó có item đó không, trạng thái active
        CartItem item = cartItemRepository.findByIdAndCart_UserIdAndCart_Status(itemId, userId, ACTIVE)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND));

        //issue yêu cầu set số lượng chứ không add
        item.setQuantity(request.getQuantity());
        return toResponse(cart);
    }

    @Transactional
    public void removeCartItem(CurrentUser currentUser, UUID itemId) {
        //kiểm tra quyền tránh user này cập nhật cart user khác
        UUID userId = requireUserId(currentUser);

        //tìm cart, nếu k có hoặc k active thì 404 not found
        Cart cart = getActiveCartOrThrow(userId);

        if (cartRepository.touchActiveCart(cart.getId()) == 0) {
            throw new DomainException(ErrorCode.NOT_FOUND);
        }

        CartItem item = cartItemRepository.findByIdAndCart_UserIdAndCart_Status(itemId, userId, ACTIVE)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND));

        cartItemRepository.delete(item);
    }

    // Đảm bảo user đã đăng nhập (guest cart không được hỗ trợ trong R1).
    private UUID requireUserId(CurrentUser currentUser) {
        if (currentUser == null || currentUser.id() == null) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED);
        }
        return currentUser.id();
    }

    private Cart getActiveCartOrThrow(UUID userId) {
        return cartRepository.findByUserIdAndStatus(userId, ACTIVE)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND));
    }

    // Lấy cart ACTIVE của user, tạo mới nếu chưa có.
    // ON CONFLICT + partial unique index idx_carts_one_active đảm bảo
    // không bao giờ có hai cart ACTIVE cho cùng một user,
    // kể cả khi nhiều request chạy đồng thời.
    private Cart getOrCreateActiveCart(UUID userId) {
        cartRepository.createActiveCartIfAbsent(UUID.randomUUID(), userId);
        return cartRepository.findByUserIdAndStatus(userId, ACTIVE).orElseThrow(() -> {
                    log.error(
                            "Active cart not found after create-or-get. userId={}", userId);
                throw new  DomainException(ErrorCode.DB_ERROR);
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
                item.getUnitPrice()
        );
    }
}
