package com.group5.commerceservice.cart.service;

import com.group5.commerceservice.cart.CartStatus;
import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.dto.CatalogSkuResponse;
import com.group5.commerceservice.cart.dto.response.CartIssue;
import com.group5.commerceservice.cart.dto.response.CartPricingResponse;
import com.group5.commerceservice.cart.dto.response.CartValidationResponse;
import com.group5.commerceservice.cart.entity.Cart;
import com.group5.commerceservice.cart.entity.CartItem;
import com.group5.commerceservice.cart.repository.CartItemRepository;
import com.group5.commerceservice.cart.repository.CartRepository;
import com.group5.commerceservice.checkout.dto.CheckoutPreviewResponse;
import com.group5.commerceservice.checkout.dto.CheckoutPreviewResponse.Item;
import com.group5.commerceservice.shared.config.CheckoutProperties;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.group5.commerceservice.cart.dto.response.CartIssue.Code.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CartPricingService {
    private final CartRepository carts;
    private final CartItemRepository cartItems;
    private final CatalogClient catalog;
    private final CheckoutProperties properties;

    public CartPricingResponse pricing(CurrentUser user, String promoCode) {
        Calculation result = calculate(user, false);
        return new CartPricingResponse(result.subtotal(), BigDecimal.ZERO, BigDecimal.ZERO, null, result.subtotal());
    }

    public CartValidationResponse validate(CurrentUser user) {
        Calculation result = calculate(user, false);
        return new CartValidationResponse(result.issues().isEmpty(), result.issues());
    }

    public CheckoutPreviewResponse preview(CurrentUser user, String promoCode) {
        Calculation result = calculate(user, true);
        return new CheckoutPreviewResponse(result.items(), result.subtotal(), null,
                BigDecimal.ZERO, BigDecimal.ZERO, result.subtotal(), result.issues());
    }

    public List<CartIssue> mergeIssues(CurrentUser user) {
        return calculate(user, false, true).issues();
    }

    private Calculation calculate(CurrentUser user, boolean rejectEmpty) {
        return calculate(user, rejectEmpty, false);
    }

    private Calculation calculate(CurrentUser user, boolean rejectEmpty, boolean merging) {
        if (user == null || user.id() == null) throw new DomainException(CommerceErrorCode.UNAUTHENTICATED);
        if (!properties.isPriceRevalidation()) {
            throw new DomainException(CommerceErrorCode.INVALID_PRICING_CONFIGURATION);
        }
        //lấy ra cart trước -> dựa theo cart lấy ra list các cartItem, nếu k có thì list rỗng
        List<CartItem> listCartItem = carts.findByUserIdAndStatus(user.id(), CartStatus.ACTIVE)
                .map(cart -> cartItems.findByCart_Id(cart.getId())).orElseGet(List::of);
        if (rejectEmpty && listCartItem.isEmpty()) throw new DomainException(CommerceErrorCode.CART_EMPTY);

        List<Item> items = new ArrayList<>();
        List<CartIssue> issues = new ArrayList<>();
        //lưu product theo productId
        Map<UUID, CatalogClient.ProductResponse> products = new HashMap<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (CartItem line : listCartItem) {
            if (line.getQuantity() == null || line.getQuantity() <= 0) {
                throw new DomainException(CommerceErrorCode.INVALID_QUANTITY);
            }
            CatalogSkuResponse sku = readSku(line);
            //nếu Map chưa có productId này...
            if (!products.containsKey(line.getProductId())) {
                //thì gọi hàm readProduct() để gắn products vào productId này
                products.put(line.getProductId(), readProduct(line.getProductId()));
            }
            //else nếu map có productId đó rùi - lấy ra product được bth
            CatalogClient.ProductResponse product = products.get(line.getProductId());
            if (merging && product != null && user.id().equals(product.sellerId())) {
                issues.add(new CartIssue(line.getId(), line.getSkuId(), SELF_PURCHASE));
            }
            //
            if (sku == null || product == null || !sku.getStatus().equals("ACTIVE")
                    || !product.status().equals("ACTIVE")) {
                //có lỗi thì cho cái line(CartItems) đó vào issueList
                issues.add(new CartIssue(line.getId(), line.getSkuId(), PRODUCT_INACTIVE));
            }
            //chỗ này mốt sửa phần giá lại do skus phiên bản mới sẽ có 2 giá ( giá nhập hàng, giá bán ra )
            //line = cartItem
            if (sku != null && sku.getPrice().compareTo(line.getUnitPrice()) != 0) {
                issues.add(new CartIssue(line.getId(), line.getSkuId(), PRICE_CHANGED));
            }
            // do trong cartItem vẫn có price, sku cũng có price
            BigDecimal unitPrice = sku == null ? line.getUnitPrice() : sku.getPrice();

            //phép nhân (giá nhân số lượng)
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(line.getQuantity()));


            items.add(new Item(line.getId(), line.getProductId(), line.getSkuId(), line.getQuantity(), unitPrice, lineTotal));
            subtotal = subtotal.add(lineTotal);
            //chưa có promotion nên để mốt update sau
        }

        return new Calculation(List.copyOf(items), subtotal, List.copyOf(issues));
    }

    private CatalogSkuResponse readSku(CartItem line) {
        try {
            var response = catalog.getSkuById(line.getSkuId());
            CatalogSkuResponse sku = response == null ? null : response.data();
            if (sku == null || !line.getSkuId().equals(sku.getId())
                    || !line.getProductId().equals(sku.getProductId()) || sku.getStatus() == null
                    || sku.getPrice() == null || sku.getPrice().signum() < 0) {
                throw new DomainException(CommerceErrorCode.INVALID_CATALOG_RESPONSE);
            }
            return sku;
        } catch (FeignException.NotFound exception) {
            return null;
        } catch (FeignException exception) {
            throw new DomainException(CommerceErrorCode.CATALOG_UNAVAILABLE);
        }
    }

    private CatalogClient.ProductResponse readProduct(UUID productId) {
        try {
            var response = catalog.getProductById(productId);
            CatalogClient.ProductResponse product = response == null ? null : response.data();
            if (product == null || !productId.equals(product.id()) || product.status() == null
                    || product.sellerId() == null) {
                throw new DomainException(CommerceErrorCode.INVALID_CATALOG_RESPONSE);
            }
            return product;
        } catch (FeignException.NotFound exception) {
            return null;
        } catch (FeignException exception) {
            throw new DomainException(CommerceErrorCode.CATALOG_UNAVAILABLE);
        }
    }

    private record Calculation(List<Item> items, BigDecimal subtotal, List<CartIssue> issues) { }
}
