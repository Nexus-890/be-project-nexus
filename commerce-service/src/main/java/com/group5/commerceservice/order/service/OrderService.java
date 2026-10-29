package com.group5.commerceservice.order.service;

import com.group5.commerceservice.cart.CartStatus;
import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.dto.CatalogClient.ProductResponse;
import com.group5.commerceservice.cart.dto.CatalogSkuResponse;
import com.group5.commerceservice.cart.entity.Cart;
import com.group5.commerceservice.cart.entity.CartItem;
import com.group5.commerceservice.cart.repository.CartItemRepository;
import com.group5.commerceservice.cart.repository.CartRepository;
import com.group5.commerceservice.order.dto.request.ChangeOrderStatusRequest;
import com.group5.commerceservice.order.dto.request.CreateOrderRequest;
import com.group5.commerceservice.order.dto.response.OrderGroupResponse;
import com.group5.commerceservice.order.dto.response.OrderItemResponse;
import com.group5.commerceservice.order.dto.response.OrderResponse;
import com.group5.commerceservice.order.dto.response.OrderStatusHistoryResponse;
import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.entity.OrderGroup;
import com.group5.commerceservice.order.entity.OrderItem;
import com.group5.commerceservice.order.entity.OrderStatusHistory;
import com.group5.commerceservice.order.repository.OrderGroupRepository;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.repository.OrderStatusHistoryRepository;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import feign.FeignException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderGroupRepository groupRepository;
    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final CatalogClient catalogClient;
    private final InventoryClient inventoryClient;
    private final OrderStateMachine stateMachine;
    private final OrderStatusHistoryRepository historyRepository;

    @Value("${nexus.order.payment-timeout-minutes:15}")
    private long paymentTimeoutMinutes = 15;

    @PostConstruct
    void validateConfiguration() {
        if (paymentTimeoutMinutes < 1) {
            throw new IllegalStateException("Order payment timeout must be positive");
        }
    }

    public record CreationResult(boolean created, OrderGroupResponse response) {
    }

    //Create order form cart
    @Transactional
    public CreationResult create(CurrentUser user, String key, CreateOrderRequest request) {
        log.info("Create order called: buyerId={}, idempotencyKey={}",
                user != null ? user.id() : null, key);
        requireAccess(user);
        if (key == null || !key.matches("[\\x21-\\x7E]{1,100}")) {
            throw new DomainException(CommerceErrorCode.INVALID_ORDER_REQUEST, "Idempotency-Key must contain 1 to 100 visible ASCII characters");
        }
        if (request == null || request.shippingAddress() == null || !"SEPAY".equals(request.paymentMethod())) {
            throw new DomainException(CommerceErrorCode.INVALID_ORDER_REQUEST, "Shipping address and SEPAY payment method are required");
        }
        Map<String, Object> snapshot = request.snapshot();
        groupRepository.lockKey("order-key:" + key);
        Optional<OrderGroup> previous = groupRepository.findByIdempotencyKey(key);
        if (previous.isPresent()) {
            OrderGroup group = previous.get();
            if (!group.getBuyerId().equals(user.id()) || group.getOrders().isEmpty()
                    || group.getOrders().stream().anyMatch(order -> !snapshot.equals(order.getShippingInfo()))) {
                throw new DomainException(CommerceErrorCode.IDEMPOTENCY_CONFLICT);
            }
            return new CreationResult(false, toResponse(group));
        }

        groupRepository.lockKey("order-buyer:" + user.id());
        Cart cart = cartRepository.findActiveForUpdate(user.id())
                .orElseThrow(() -> new DomainException(CommerceErrorCode.CART_EMPTY));
        List<CartItem> lines = cartItemRepository.findByCart_Id(cart.getId());
        if (lines.isEmpty()) {
            throw new DomainException(CommerceErrorCode.CART_EMPTY);
        }
        // Promotion pricing is a separate issue; never silently drop an already selected voucher.
        if (cart.getPromoCode() != null && !cart.getPromoCode().isBlank()) {
            throw new DomainException(CommerceErrorCode.PROMOTION_NOT_SUPPORTED);
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        OrderGroup group = new OrderGroup();
        group.setBuyerId(user.id());
        group.setIdempotencyKey(key);
        group.setDueAt(now.plusMinutes(paymentTimeoutMinutes));
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        group.setCreatedBy(user.id());
        group.setUpdatedBy(user.id());
        Map<UUID, Order> sellers = new TreeMap<>();
        Map<UUID, ProductResponse> products = new HashMap<>();
        for (CartItem line : lines.stream().sorted(Comparator.comparing(CartItem::getId)).toList()) {
            CatalogSkuResponse sku = readSku(line);
            ProductResponse product = products.computeIfAbsent(line.getProductId(), this::readProduct);
            if (line.getQuantity() == null || line.getQuantity() <= 0) {
                throw new DomainException(CommerceErrorCode.INVALID_QUANTITY);
            }
            if (!inventoryClient.reserve(line.getProductId(), line.getSkuId(), line.getQuantity())) {
                throw new DomainException(CommerceErrorCode.ITEM_UNAVAILABLE);
            }
            Order order = sellers.computeIfAbsent(product.sellerId(), sellerId -> {
                Order child = new Order();
                child.setOrderGroup(group);
                child.setUserId(user.id());
                child.setSellerId(sellerId);
                child.setOrderNumber(nextOrderNumber(now));
                child.setIdempotencyKey(childKey(user.id(), key, sellerId));
                child.setShippingInfo(snapshot);
                child.setPaymentDueAt(group.getDueAt());
                child.setCreatedAt(now);
                child.setUpdatedAt(now);
                return child;
            });
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProductId(line.getProductId());
            item.setSkuId(line.getSkuId());
            item.setProductName(product.name());
            item.setSkuCode(sku.getSkuCode());
            item.setImageUrl(firstImage(product));
            item.setQuantity(line.getQuantity());
            item.setUnitPrice(money(sku.getPrice()));
            item.setTotalPrice(money(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()))));
            order.getItems().add(item);
        }
        for (Order order : sellers.values()) {
            BigDecimal subtotal = order.getItems().stream().map(OrderItem::getTotalPrice)
                    .reduce(new BigDecimal("0.00"), BigDecimal::add);
            order.setSubtotal(money(subtotal));
            order.setTotalAmount(order.getSubtotal());
            group.getOrders().add(order);
        }
        group.setTotalAmount(money(group.getOrders().stream().map(Order::getTotalAmount)
                .reduce(new BigDecimal("0.00"), BigDecimal::add)));
        if (group.getTotalAmount().signum() <= 0) {
            throw new DomainException(CommerceErrorCode.INVALID_ORDER_REQUEST, "SEPAY orders must have a positive total");
        }
        groupRepository.saveAndFlush(group);
        group.getOrders().forEach(order -> stateMachine.recordCreated(order.getId(), user.id()));

        log.info("Cart before checkout: id={}, status={}",
                cart.getId(), cart.getStatus());

        cart.setStatus(CartStatus.CHECKED_OUT);
        cartRepository.saveAndFlush(cart);

        log.info("Cart after checkout: id={}, status={}", cart.getId(), cart.getStatus());
        log.info("Order group created: groupId={}, buyerId={}, orders={}", group.getId(), user.id(), sellers.size());
        return new CreationResult(true, toResponse(group));
    }

    @Transactional(readOnly = true)
    public boolean hasProductReferences(UUID productId) {
        return orderRepository.hasProductReferences(productId);
    }

    @Transactional
    public OrderResponse changeStatus(CurrentUser user, UUID orderId, ChangeOrderStatusRequest request) {
        if (user == null || user.id() == null) {
            throw new DomainException(CommerceErrorCode.UNAUTHENTICATED);
        }
        if (!user.hasRole("ADMIN")) {
            throw new DomainException(CommerceErrorCode.FORBIDDEN);
        }
        stateMachine.move(orderId, request.status(), user.id(), request.reason());
        return orderRepository.findById(orderId).map(this::toOrderResponse)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<OrderStatusHistoryResponse> statusHistory(CurrentUser user, UUID orderId) {
        if (user == null || user.id() == null) {
            throw new DomainException(CommerceErrorCode.UNAUTHENTICATED);
        }
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(user.id()) && !user.hasRole("ADMIN")) {
            throw new DomainException(CommerceErrorCode.FORBIDDEN);
        }
        return historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId).stream()
                .map(this::toHistoryResponse)
                .toList();
    }

    private CatalogSkuResponse readSku(CartItem line) {
        CatalogSkuResponse sku;
        try {
            var response = catalogClient.getSkuById(line.getSkuId());
            sku = response == null ? null : response.data();
        } catch (FeignException.NotFound exception) {
            throw new DomainException(CommerceErrorCode.ITEM_UNAVAILABLE, "A cart item is no longer available");
        } catch (FeignException exception) {
            throw new DomainException(CommerceErrorCode.CATALOG_UNAVAILABLE, "Catalog is unavailable; retry placing the order later");
        }
        if (sku == null || !line.getSkuId().equals(sku.getId()) || !line.getProductId().equals(sku.getProductId())
                || !"ACTIVE".equals(sku.getStatus()) || sku.getPrice() == null || sku.getPrice().signum() < 0) {
            throw new DomainException(CommerceErrorCode.ITEM_UNAVAILABLE, "A cart item is no longer available");
        }
        return sku;
    }

    private ProductResponse readProduct(UUID id) {
        ProductResponse product;
        try {
            var response = catalogClient.getProductById(id);
            product = response == null ? null : response.data();
        } catch (FeignException.NotFound exception) {
            throw new DomainException(CommerceErrorCode.ITEM_UNAVAILABLE, "A cart product is no longer available");
        } catch (FeignException exception) {
            throw new DomainException(CommerceErrorCode.CATALOG_UNAVAILABLE, "Catalog is unavailable; retry placing the order later");
        }
        if (product == null || !id.equals(product.id()) || product.sellerId() == null
                || product.name() == null || product.name().isBlank() || !"ACTIVE".equals(product.status())) {
            throw new DomainException(CommerceErrorCode.ITEM_UNAVAILABLE, "A cart product is no longer available");
        }
        return product;
    }

    // Chọn image
    private String firstImage(ProductResponse product) {
        if (product.images() == null) {
            return null;
        }
        return product.images().stream().filter(Objects::nonNull)
                .filter(image -> image.imageUrl() != null)
                .min(Comparator.comparing(CatalogClient.ImageResponse::sortOrder,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(CatalogClient.ImageResponse::imageUrl).orElse(null);
    }

    private BigDecimal money(BigDecimal value) {
        try {
            BigDecimal result = value.setScale(2, RoundingMode.UNNECESSARY);
            if (result.precision() > 19) {
                throw new ArithmeticException("Money overflow");
            }
            return result;
        } catch (ArithmeticException exception) {
            throw new DomainException(CommerceErrorCode.INVALID_ORDER_AMOUNT);
        }
    }

    private void requireAccess(CurrentUser user) {
        if (user == null || user.id() == null) {
            throw new DomainException(CommerceErrorCode.UNAUTHENTICATED);
        }
        if (!user.hasPrivilege("CHECKOUT.START") || !user.hasPrivilege("ORDER.CREATE")) {
            throw new DomainException(CommerceErrorCode.FORBIDDEN, "CHECKOUT.START and ORDER.CREATE are required");
        }
    }

    private String nextOrderNumber(OffsetDateTime now) {
        return "NX-" + now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + String.format(Locale.ROOT, "%08d", orderRepository.nextOrderSequence());
    }

    private String childKey(UUID buyerId, String groupKey, UUID sellerId) {
        StringBuilder tuple = new StringBuilder();
        for (String value : new String[]{buyerId.toString(), groupKey, sellerId.toString()}) {
            tuple.append(value.length()).append(':').append(value);
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(tuple.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    @Transactional(readOnly = true)
    public List<OrderGroupResponse> history(CurrentUser user) {
        if (user == null || user.id() == null) {
            throw new DomainException(CommerceErrorCode.UNAUTHENTICATED);
        }

        return groupRepository.findByBuyerIdOrderByCreatedAtDesc(user.id())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private OrderGroupResponse toResponse(OrderGroup group) {
        List<OrderResponse> orders = group.getOrders().stream()
                .sorted(Comparator.comparing(Order::getOrderNumber))
                .map(this::toOrderResponse)
                .toList();
        return new OrderGroupResponse(group.getId(), group.getStatus(), group.getTotalAmount(),
                group.getCurrency(), group.getDueAt(), orders);
    }

    private OrderResponse toOrderResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .sorted(Comparator.comparing(OrderItem::getId))
                .map(this::toItemResponse)
                .toList();
        return new OrderResponse(order.getId(), order.getOrderNumber(), order.getSellerId(),
                order.getStatus(), order.getTotalAmount(), items);
    }

    private OrderStatusHistoryResponse toHistoryResponse(OrderStatusHistory entry) {
        return new OrderStatusHistoryResponse(entry.getId(), entry.getPreviousStatus(), entry.getNewStatus(),
                entry.getReason(), entry.getChangedBy(), entry.getCreatedAt());
    }

    private OrderItemResponse toItemResponse(OrderItem item) {
        return new OrderItemResponse(item.getId(), item.getProductId(), item.getSkuId(),
                item.getProductName(), item.getSkuCode(), item.getImageUrl(), item.getQuantity(),
                item.getUnitPrice(), item.getTotalPrice());
    }
}
