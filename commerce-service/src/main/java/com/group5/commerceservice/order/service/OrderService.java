package com.group5.commerceservice.order.service;

import com.group5.commerceservice.cart.CartStatus;
import com.group5.commerceservice.cart.dto.CatalogClient;
import com.group5.commerceservice.cart.dto.CatalogClient.ProductResponse;
import com.group5.commerceservice.cart.dto.CatalogSkuResponse;
import com.group5.commerceservice.cart.entity.Cart;
import com.group5.commerceservice.cart.entity.CartItem;
import com.group5.commerceservice.cart.repository.CartItemRepository;
import com.group5.commerceservice.cart.repository.CartRepository;
import com.group5.commerceservice.order.OrderStatus;
import com.group5.commerceservice.order.dto.request.CancelOrderRequest;
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
import com.group5.commerceservice.order.event.OrderCancelled;
import com.group5.commerceservice.order.event.OrderEventPublisher;
import com.group5.commerceservice.order.event.OrderGroupCancelled;
import com.group5.commerceservice.order.repository.OrderGroupRepository;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.repository.OrderStatusHistoryRepository;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import com.group5.common_core.constant.CommonErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import feign.FeignException;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final PricingService pricingService;
    private static final String PAYMENT_DEADLINE_EXPIRED = "PAYMENT_DEADLINE_EXPIRED";

    private final OrderStateMachine stateMachine;
    private final OrderStatusHistoryRepository historyRepository;
    private final PlatformTransactionManager transactionManager;
    private final OrderEventPublisher events;
    private final EntityManager entityManager;

    @Value("${nexus.order.idempotency-enabled:true}")
    private boolean idempotencyEnabled = true;

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

    private record CheckoutResult(CreationResult order, DomainException priceChange) {
    }

    // Each attempt must finish (or roll back) before reading a UNIQUE-conflict winner.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CreationResult create(CurrentUser user, String key, CreateOrderRequest request) {
        requireAccess(user);
        if (key == null || !key.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new DomainException(CommerceErrorCode.INVALID_ORDER_REQUEST, "Idempotency-Key must be a UUID");
        }
        if (request == null || request.shippingAddress() == null || !"SEPAY".equals(request.paymentMethod())) {
            throw new DomainException(CommerceErrorCode.INVALID_ORDER_REQUEST, "Shipping address and SEPAY payment method are required");
        }
        Map<String, Object> snapshot = request.snapshot();
        boolean replayEnabled = idempotencyEnabled;
        String effectiveKey = replayEnabled ? UUID.fromString(key).toString() : UUID.randomUUID().toString();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        CheckoutResult result;
        try {
            result = Objects.requireNonNull(transaction.execute(status -> {
                if (replayEnabled) {
                    groupRepository.lockKey("order-key:" + effectiveKey);
                    Optional<OrderGroup> previous = groupRepository.findByIdempotencyKey(effectiveKey);
                    if (previous.isPresent()) {
                        return new CheckoutResult(replay(user, snapshot, previous.get()), null);
                    }
                }
                return createFromCart(user, effectiveKey, snapshot);
            }));
        } catch (DataIntegrityViolationException violation) {
            if (!replayEnabled || !isIdempotencyKeyViolation(violation)) {
                throw violation;
            }
            // PostgreSQL aborts the losing transaction on 23505; query only after rollback.
            transaction.setReadOnly(true);
            return transaction.execute(status -> groupRepository.findByIdempotencyKey(effectiveKey)
                    .map(group -> replay(user, snapshot, group))
                    .orElseThrow(() -> violation));
        }
        // Commit the cart price refresh before the shared handler receives the conflict.
        if (result.priceChange() != null) {
            throw result.priceChange();
        }
        return result.order();
    }

    private CreationResult replay(CurrentUser user, Map<String, Object> snapshot, OrderGroup group) {
        if (!group.getBuyerId().equals(user.id())) {
            throw new DomainException(CommerceErrorCode.IDEMPOTENCY_KEY_NOT_OWNED);
        }
        if (group.getOrders().isEmpty()
                || group.getOrders().stream().anyMatch(order -> !snapshot.equals(order.getShippingInfo()))) {
            throw new DomainException(CommerceErrorCode.IDEMPOTENCY_CONFLICT);
        }
        return new CreationResult(false, toResponse(group));
    }

    private boolean isIdempotencyKeyViolation(DataIntegrityViolationException violation) {
        for (Throwable cause = violation; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint
                    && "23505".equals(constraint.getSQLState())
                    && ("order_groups_idempotency_key_key".equals(constraint.getConstraintName())
                    || "orders_idempotency_key_key".equals(constraint.getConstraintName()))) {
                return true;
            }
        }
        return false;
    }

    private CheckoutResult createFromCart(CurrentUser user, String key, Map<String, Object> snapshot) {
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

        Map<UUID, CatalogSkuResponse> skus;
        try {
            skus = pricingService.revalidate(lines);
        } catch (DomainException error) {
            if (!CommerceErrorCode.PRICE_CHANGED.code().equals(error.getErrorCode())) {
                throw error;
            }
            return new CheckoutResult(null, error);
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
            CatalogSkuResponse sku = skus.get(line.getSkuId());
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
            item.setUnitPrice(money(line.getUnitPrice()));
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
        OrderGroup savedGroup = groupRepository.saveAndFlush(group);
        savedGroup.getOrders().forEach(order -> stateMachine.recordCreated(order.getId(), user.id()));

        log.info("Cart before checkout: id={}, status={}",
                cart.getId(), cart.getStatus());

        cart.setStatus(CartStatus.CHECKED_OUT);
        cartRepository.saveAndFlush(cart);

        // Refresh the persisted deadlines and versions after reservation and all checkout writes.
        entityManager.refresh(savedGroup);
        publishCreation(savedGroup);

        log.info("Cart after checkout: id={}, status={}", cart.getId(), cart.getStatus());
        log.info("Order group created: groupId={}, buyerId={}, orders={}", group.getId(), user.id(), sellers.size());
        return new CheckoutResult(new CreationResult(true, toResponse(savedGroup)), null);
    }

    private void publishCreation(OrderGroup group) {
        group.getOrders().stream().sorted(Comparator.comparing(Order::getId)).forEach(events::orderCreated);
        events.checkoutPaymentRequested(group);
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

    @Transactional
    public OrderGroupResponse cancel(CurrentUser user, UUID orderId, CancelOrderRequest request) {
        if (user == null || user.id() == null) {
            throw new DomainException(CommerceErrorCode.UNAUTHENTICATED);
        }
        String reason = request.reason().strip();
        if (PAYMENT_DEADLINE_EXPIRED.equals(reason)) {
            throw new DomainException(CommerceErrorCode.RESERVED_CANCEL_REASON);
        }
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(user.id()) && !user.hasRole("ADMIN") && !user.hasRole("SUPPORT_STAFF")) {
            throw new DomainException(CommerceErrorCode.FORBIDDEN);
        }
        UUID groupId = order.getOrderGroup().getId();
        String groupStatus = groupRepository.lockStatus(groupId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        orderRepository.lockGroupOrders(groupId);
        OrderStatus current = orderRepository.findStatusById(orderId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        OffsetDateTime cancelledAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);

        List<UUID> cancelled = switch (current) {
            case PENDING -> cancelPendingGroup(groupId, groupStatus, user.id(), reason);
            case PAID -> {
                stateMachine.move(orderId, OrderStatus.CANCELED, user.id(), reason);
                yield List.of(orderId);
            }
            default -> throw notCancellable(order.getOrderNumber(), current.name());
        };
        log.info("Order cancellation accepted: orderId={}, from={}, cancelled={}, actor={}",
                orderId, current, cancelled, user.id());

        entityManager.flush();
        entityManager.clear();
        if (current == OrderStatus.PENDING) {
            returnToCart(order.getUserId(), cancelled.stream().filter(id -> !id.equals(orderId)).toList(), user.id());
        }
        OrderGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        publishCancellation(group, cancelled, current, user.id(), reason, cancelledAt.toInstant().toString());
        return toResponse(group);
    }

    private void returnToCart(UUID buyerId, List<UUID> orderIds, UUID actor) {
        List<OrderItem> items = orderIds.isEmpty() ? List.of() : orderRepository.findItemsByOrderIds(orderIds);
        if (items.isEmpty()) {
            return;
        }
        cartRepository.createActiveCartIfAbsent(UUID.randomUUID(), buyerId, actor);
        Cart cart = cartRepository.findActiveForUpdate(buyerId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.DB_ERROR));
        cartRepository.touchActiveCart(cart.getId(), actor);
        for (OrderItem item : items) {
            cartItemRepository.addOrIncreaseQuantity(UUID.randomUUID(), cart.getId(), item.getProductId(),
                    item.getSkuId(), item.getQuantity(), item.getUnitPrice(), actor);
        }
        log.info("Items of the other cancelled orders returned to the cart: buyerId={}, cartId={}, orders={}",
                buyerId, cart.getId(), orderIds);
    }

    private List<UUID> cancelPendingGroup(UUID groupId, String groupStatus, UUID actor, String reason) {
        if (!"PENDING".equals(groupStatus)) {
            throw notCancellable("group " + groupId, groupStatus);
        }
        List<UUID> pending = orderRepository.findIdsByGroupAndStatus(groupId, OrderStatus.PENDING);
        pending.forEach(id -> stateMachine.move(id, OrderStatus.CANCELED, actor, reason));
        if (groupRepository.cancelPending(groupId, actor) == 0) {
            throw notCancellable("group " + groupId, groupStatus);
        }
        return pending;
    }

    private void publishCancellation(OrderGroup group, List<UUID> cancelled, OrderStatus from, UUID actor,
                                     String reason, String cancelledAt) {
        List<Order> orders = group.getOrders().stream()
                .filter(order -> cancelled.contains(order.getId()))
                .sorted(Comparator.comparing(Order::getId))
                .toList();
        if (from == OrderStatus.PENDING) {
            events.orderGroupCancelled(new OrderGroupCancelled(group.getId(), group.getBuyerId(), group.getVersion(),
                    reason, actor, cancelledAt, orders.stream()
                            .map(order -> new OrderGroupCancelled.CancelledOrder(order.getId(), order.getVersion()))
                            .toList()));
        }
        for (Order order : orders) {
            String paidAmount = from == OrderStatus.PAID ? money(order.getTotalAmount()).toPlainString() : "0.00";
            events.orderCancelled(new OrderCancelled(order.getId(), order.getUserId(), order.getOrderNumber(),
                    order.getOrderType(), order.getReferenceId(), cancelledAt, reason, group.getId(),
                    order.getSellerId(), order.getVersion(), paidAmount, group.getCurrency(), actor));
        }
    }

    private static DomainException notCancellable(String subject, String status) {
        return new DomainException(CommerceErrorCode.ORDER_NOT_CANCELLABLE,
                subject + " is " + status + " and can no longer be cancelled");
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(CurrentUser user, UUID orderId) {
        if (user == null || user.id() == null) {
            throw new DomainException(CommerceErrorCode.UNAUTHENTICATED);
        }
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(user.id()) && !user.hasRole("ADMIN")) {
            throw new DomainException(CommerceErrorCode.FORBIDDEN);
        }
        return toOrderResponse(order);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> listOwn(CurrentUser user, OrderStatus status, int page, int size) {
        if (user == null || user.id() == null) {
            throw new DomainException(CommerceErrorCode.UNAUTHENTICATED);
        }
        if (page < 0 || size < 1 || size > 100) {
            throw new DomainException(CommonErrorCode.INVALID_PARAMETER, "page must be 0 or more and size between 1 and 100");
        }
        Pageable pageable = PageRequest.of(page, size);
        Page<Order> orders = status == null
                ? orderRepository.findByUserIdOrderByCreatedAtDescOrderNumberDesc(user.id(), pageable)
                : orderRepository.findByUserIdAndStatusOrderByCreatedAtDescOrderNumberDesc(user.id(), status, pageable);
        return orders.map(this::toOrderResponse);
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
