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
import com.group5.commerceservice.checkout.dto.CheckoutPreviewRequest;
import com.group5.commerceservice.order.service.OrderShippingService;
import com.group5.commerceservice.order.service.PricingService;
import com.group5.commerceservice.promotion.VoucherCheckoutService;
import com.group5.commerceservice.promotion.VoucherCodes;
import com.group5.commerceservice.promotion.VoucherPricing;
import com.group5.commerceservice.shared.config.CheckoutProperties;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import com.group5.commerceservice.shared.pricing.PriceBreakdown;
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
    private final PricingService orderPricing;
    private final VoucherCheckoutService vouchers;

    public CartPricingResponse pricing(CurrentUser user, String promoCode, List<String> voucherCodes) {
        Calculation result = calculate(user, false);
        var quote = quote(user.id(), result, VoucherCodes.requested(voucherCodes, promoCode));
        PriceBreakdown price = breakdown(result, quote, null);
        return new CartPricingResponse(price.subtotal(), price.taxAmount(), price.discountAmount(),
                price.shippingFee(), price.total(), quote.vouchers());
    }

    public CartValidationResponse validate(CurrentUser user) {
        Calculation result = calculate(user, false);
        return new CartValidationResponse(result.issues().isEmpty(), result.issues());
    }

    public CheckoutPreviewResponse preview(CurrentUser user, String promoCode) {
        Calculation result = calculate(user, true);
        var quote = quote(user.id(), result, VoucherCodes.requested(null, promoCode));
        return preview(result, quote, null);
    }

    /** Preview và tạo đơn cùng gọi báo giá theo shop từ metadata Catalog. Không giữ tồn kho. */
    public CheckoutPreviewResponse previewCheckout(CurrentUser user, CheckoutPreviewRequest request) {
        Calculation result = calculate(user, true);
        var quote = quote(user.id(), result, VoucherCodes.requested(request.voucherCodes(), request.promoCode()));
        if (result.issues().stream().anyMatch(issue -> issue.code() == PRODUCT_INACTIVE)) {
            return preview(result, quote, null);
        }
        Map<UUID, List<OrderShippingService.Line>> sellers = new java.util.TreeMap<>();
        for (Item item : result.items()) {
            CatalogClient.ProductResponse product = result.products().get(item.productId());
            if (product.sellerId() == null) throw new DomainException(CommerceErrorCode.INVALID_CATALOG_RESPONSE);
            sellers.computeIfAbsent(product.sellerId(), ignored -> new ArrayList<>()).add(
                    new OrderShippingService.Line(item.skuId(), item.quantity(), result.skus().get(item.skuId())));
        }
        BigDecimal shipping = BigDecimal.ZERO;
        for (var seller : sellers.values()) {
            shipping = shipping.add(orderPricing.shippingQuote(request.shippingAddress(), seller).fee());
        }
        return preview(result, quote, shipping);
    }

    private static CheckoutPreviewResponse preview(Calculation result, VoucherPricing.Result quote, BigDecimal shipping) {
        PriceBreakdown price = breakdown(result, quote, shipping);
        return new CheckoutPreviewResponse(result.items(), price.subtotal(), price.shippingFee(),
                price.taxAmount(), price.discountAmount(), price.total(), result.issues(), quote.vouchers());
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
        var active = carts.findByUserIdAndStatus(user.id(), CartStatus.ACTIVE);
        List<CartItem> listCartItem = active.map(cart -> cartItems.findByCart_Id(cart.getId())).orElseGet(List::of);
        String cartCode = active.map(Cart::getPromoCode).orElse(null);
        if (rejectEmpty && listCartItem.isEmpty()) throw new DomainException(CommerceErrorCode.CART_EMPTY);

        List<Item> items = new ArrayList<>();
        List<CartIssue> issues = new ArrayList<>();
        //lưu product theo productId
        Map<UUID, CatalogClient.ProductResponse> products = new HashMap<>();
        Map<UUID, CatalogSkuResponse> skus = new HashMap<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (CartItem line : listCartItem) {
            if (line.getQuantity() == null || line.getQuantity() <= 0) {
                throw new DomainException(CommerceErrorCode.INVALID_QUANTITY);
            }
            CatalogSkuResponse sku = readSku(line);
            skus.put(line.getSkuId(), sku);
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
        }

        return new Calculation(List.copyOf(items), subtotal, List.copyOf(issues), products, skus, cartCode);
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

    private VoucherPricing.Result quote(UUID buyer, Calculation result, List<String> requested) {
        var codes = VoucherCodes.withCartFallback(requested, result.cartCode());
        if (codes.isEmpty()) return new VoucherPricing.Result(BigDecimal.ZERO, List.of(), List.of());
        var inactive = result.issues().stream().filter(issue -> issue.code() == PRODUCT_INACTIVE)
                .map(CartIssue::skuId).collect(java.util.stream.Collectors.toSet());
        var lines = result.items().stream().filter(item -> !inactive.contains(item.skuId()))
                .map(item -> new VoucherPricing.Line(item.skuId(), item.productId(),
                        result.products().get(item.productId()).sellerId(), item.lineTotal()))
                .toList();
        return vouchers.quote(buyer, lines, codes);
    }

    private static PriceBreakdown breakdown(Calculation result, VoucherPricing.Result quote, BigDecimal shipping) {
        return new PriceBreakdown(result.subtotal(), quote.discountAmount(), BigDecimal.ZERO, shipping);
    }

    private record Calculation(List<Item> items, BigDecimal subtotal, List<CartIssue> issues,
                               Map<UUID, CatalogClient.ProductResponse> products,
                               Map<UUID, CatalogSkuResponse> skus, String cartCode) { }
}
