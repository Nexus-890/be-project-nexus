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
import com.group5.commerceservice.order.OrderType;
import com.group5.commerceservice.order.client.config.OrderRequestDeadline;
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
import com.group5.commerceservice.promotion.VoucherCheckoutService;
import com.group5.commerceservice.promotion.VoucherCodes;
import com.group5.commerceservice.promotion.VoucherLifecycleService;
import com.group5.commerceservice.promotion.VoucherPricing;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import com.group5.commerceservice.shared.pricing.PriceBreakdown;
import com.group5.common_core.constant.CommonErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.common_security.CurrentUserAuditor;
import feign.FeignException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final String STOCK_UNAVAILABLE = "STOCK_UNAVAILABLE";

    private final OrderStateMachine stateMachine;
    private final OrderStatusHistoryRepository historyRepository;
    private final PlatformTransactionManager transactionManager;
    private final OrderEventPublisher events;
    private final EntityManager entityManager;
    private final VoucherCheckoutService vouchers;
    private final VoucherLifecycleService voucherLifecycle;

    @Value("${nexus.order.idempotency-enabled:true}")
    private boolean idempotencyEnabled = true;

    public record CreationResult(boolean created, OrderGroupResponse response) {
    }

    private record CheckoutResult(CreationResult order, DomainException priceChange) {
    }

    // Each attempt must finish (or roll back) before reading a UNIQUE-conflict winner.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CreationResult create(CurrentUser user, String key, CreateOrderRequest request) {
        try (var deadline = OrderRequestDeadline.start(2600)) {
            return createWithinDeadline(user, key, request);
        }
    }

    private CreationResult createWithinDeadline(CurrentUser user, String key, CreateOrderRequest request) {
        // Check quyền dưới hàm requireAccess
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
                return createFromCart(user, effectiveKey, request, snapshot);
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
                || group.getOrders().stream().anyMatch(order -> order.getShippingInfo() == null
                || !Objects.equals(snapshot.get("voucherCodes"), order.getShippingInfo().get("voucherCodes"))
                || snapshot.entrySet().stream().anyMatch(entry ->
                        !Objects.equals(entry.getValue(), order.getShippingInfo().get(entry.getKey()))))) {
            throw new DomainException(CommerceErrorCode.IDEMPOTENCY_CONFLICT);
        }
        return new CreationResult(false, toResponse(group, user));
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

    private CheckoutResult createFromCart(CurrentUser user, String key, CreateOrderRequest request,
                                          Map<String, Object> snapshot) {
        // Đồng bộ những lần tạo đơn của cùng một người mua
        groupRepository.lockKey("order-buyer:" + user.id());
        Cart cart = cartRepository.findForUpdateByUserIdAndStatus(user.id(), CartStatus.ACTIVE)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.CART_EMPTY));
        List<CartItem> lines = cartItemRepository.findByCart_Id(cart.getId());
        if (lines.isEmpty()) {
            throw new DomainException(CommerceErrorCode.CART_EMPTY);
        }
        List<String> codes = VoucherCodes.withCartFallback(request.requestedVouchers(), cart.getPromoCode());

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
        // Giá trị tạm trong transaction; deadline chính thức chỉ lấy từ Inventory trước commit.
        group.setDueAt(now);
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
            // Tạo order theo seller
            Order order = sellers.computeIfAbsent(product.sellerId(), sellerId -> {
                Order child = new Order();
                child.setOrderGroup(group);
                child.setUserId(user.id());
                child.setSellerId(sellerId);
                child.setOrderNumber(nextOrderNumber(now));
                child.setIdempotencyKey(childKey(user.id(), key, sellerId));
                child.setShippingInfo(new HashMap<>(snapshot));
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
            item.setUnitPrice(money(codes.isEmpty() ? line.getUnitPrice() : sku.getPrice()));
            item.setTotalPrice(money(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()))));
            order.getItems().add(item);
        }
        // Báo giá tất cả shop trước khi giữ hàng: lỗi Shipping không tạo reservation.
        for (Order order : sellers.values()) {
            BigDecimal subtotal = order.getItems().stream().map(OrderItem::getTotalPrice)
                    .reduce(new BigDecimal("0.00"), BigDecimal::add);
            order.setSubtotal(money(subtotal));
            OrderShippingService.Quote quote = pricingService.shippingQuote(request.shippingAddress(),
                    order.getItems().stream().map(item -> new OrderShippingService.Line(
                            item.getSkuId(), item.getQuantity(), skus.get(item.getSkuId()))).toList());
            order.setShippingFee(money(quote.fee()));
            order.getShippingInfo().put("parcel", Map.of("weightGrams", quote.parcel().weightGrams(),
                    "lengthCm", quote.parcel().lengthCm(), "widthCm", quote.parcel().widthCm(),
                    "heightCm", quote.parcel().heightCm()));
            order.getShippingInfo().put("carrier", quote.carrier());
            order.getShippingInfo().put("serviceType", quote.serviceType());
            group.getOrders().add(order);
        }
        OffsetDateTime quotedAt = OffsetDateTime.now(ZoneOffset.UTC);
        VoucherPricing.Result voucherQuote = reserveVouchers(user.id(), sellers, codes, quotedAt);
        for (Order order : sellers.values()) {
            order.setTotalAmount(money(new PriceBreakdown(order.getSubtotal(), order.getDiscountAmount(),
                    order.getTaxAmount(), order.getShippingFee()).total()));
        }
        group.setTotalAmount(money(group.getOrders().stream().map(Order::getTotalAmount)
                .reduce(new BigDecimal("0.00"), BigDecimal::add)));
        if (group.getTotalAmount().signum() <= 0) {
            throw new DomainException(CommerceErrorCode.INVALID_ORDER_REQUEST, "SEPAY orders must have a positive total");
        }
        OrderGroup savedGroup = groupRepository.saveAndFlush(group);
        // Một request cho toàn bộ nhóm: Inventory giữ tất cả shop hoặc rollback toàn bộ.
        InventoryClient.Reservation reservation = inventoryClient.reserve(savedGroup.getId(),
                savedGroup.getOrders().stream().flatMap(order -> order.getItems().stream().map(item ->
                        new InventoryClient.Item(order.getId(), item.getSkuId(), item.getQuantity()))).toList());
        // Chỉ dùng deadline/kho do Inventory trả về; không tự tính timeout thứ hai ở Commerce.
        savedGroup.setDueAt(reservation.expiresAt());
        savedGroup.getOrders().forEach(order -> {
            order.setPaymentDueAt(reservation.expiresAt());
            order.getShippingInfo().put("warehouseId", reservation.warehouseId().toString());
        });
        groupRepository.saveAndFlush(savedGroup);
        if (voucherQuote != null) vouchers.persistReservations(user.id(), savedGroup.getOrders(), voucherQuote, quotedAt);
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
        return new CheckoutResult(new CreationResult(true, toResponse(savedGroup, user)), null);
    }

    private VoucherPricing.Result reserveVouchers(UUID buyer, Map<UUID, Order> orders, List<String> codes,
                                                   OffsetDateTime now) {
        if (codes.isEmpty()) return null;
        List<VoucherPricing.Line> lines = orders.values().stream()
                .flatMap(order -> order.getItems().stream().map(item -> new VoucherPricing.Line(
                        item.getSkuId(), item.getProductId(), order.getSellerId(), item.getTotalPrice())))
                .toList();
        var result = vouchers.reserveQuota(buyer, lines, codes, now);
        result.applications().stream().flatMap(application -> application.allocations().stream())
                .forEach(allocation -> {
                    Order order = orders.get(allocation.line().sellerId());
                    order.setDiscountAmount(order.getDiscountAmount().add(allocation.discountAmount()));
                });
        return result;
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
        return orderRepository.findById(orderId).map(order -> toOrderResponse(order, user))
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
        if (PAYMENT_DEADLINE_EXPIRED.equals(reason) || STOCK_UNAVAILABLE.equals(reason)) {
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
        return toResponse(group, user);
    }

    @Transactional
    public List<UUID> expire(UUID groupId) {
        String groupStatus = groupRepository.lockStatus(groupId).orElse(null);
        if (!"PENDING".equals(groupStatus) || !groupRepository.isOverdue(groupId)) {
            return List.of();
        }
        orderRepository.lockGroupOrders(groupId);
        UUID system = CurrentUserAuditor.SYSTEM_ID;
        String cancelledAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS).toInstant().toString();
        List<UUID> cancelled = cancelPendingGroup(groupId, groupStatus, system, PAYMENT_DEADLINE_EXPIRED);
        entityManager.flush();
        entityManager.clear();
        OrderGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        publishCancellation(group, cancelled, OrderStatus.PENDING, system, PAYMENT_DEADLINE_EXPIRED, cancelledAt);
        log.info("Order group expired: groupId={}, cancelled={}", groupId, cancelled);
        return cancelled;
    }

    @Transactional
    public boolean cancelPaidForMissingStock(UUID orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.error("Stock unavailable for an unknown order: orderId={}", orderId);
            return false;
        }
        UUID groupId = order.getOrderGroup().getId();
        groupRepository.lockStatus(groupId);
        orderRepository.lockGroupOrders(groupId);
        OrderStatus current = orderRepository.findStatusById(orderId).orElseThrow();
        if (current != OrderStatus.PAID) {
            log.error("Stock unavailable but the order is not PAID: orderId={}, status={}", orderId, current);
            return false;
        }
        UUID system = CurrentUserAuditor.SYSTEM_ID;
        String cancelledAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS).toInstant().toString();
        stateMachine.move(orderId, OrderStatus.CANCELED, system, STOCK_UNAVAILABLE);
        entityManager.flush();
        entityManager.clear();
        OrderGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        publishCancellation(group, List.of(orderId), OrderStatus.PAID, system, STOCK_UNAVAILABLE, cancelledAt);
        log.warn("Paid order cancelled for missing stock: orderId={}, groupId={}", orderId, groupId);
        return true;
    }

    private void returnToCart(UUID buyerId, List<UUID> orderIds, UUID actor) {
        List<OrderItem> items = orderIds.isEmpty() ? List.of() : orderRepository.findItemsByOrderIds(orderIds);
        if (items.isEmpty()) {
            return;
        }
        cartRepository.createActiveCartIfAbsent(UUID.randomUUID(), buyerId, actor);
        Cart cart = cartRepository.findForUpdateByUserIdAndStatus(buyerId, CartStatus.ACTIVE)
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
            boolean expiry = PAYMENT_DEADLINE_EXPIRED.equals(reason);
            voucherLifecycle.release(cancelled, expiry, OffsetDateTime.parse(cancelledAt), actor,
                    expiry ? PAYMENT_DEADLINE_EXPIRED : "ORDER_CANCELLED");
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

    /** Trả trọn nhóm đơn cho chủ sở hữu hoặc Admin có ORDER.VIEW; không mở quyền cho buyer khác. */
    @Transactional(readOnly = true)
    public OrderGroupResponse getGroup(CurrentUser user, UUID groupId) {
        if (user == null || user.id() == null) {
            throw new DomainException(CommerceErrorCode.UNAUTHENTICATED);
        }
        if (!user.hasPrivilege("ORDER.VIEW")) {
            throw new DomainException(CommerceErrorCode.FORBIDDEN);
        }
        OrderGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        if (!group.getBuyerId().equals(user.id()) && !user.hasRole("ADMIN")) {
            throw new DomainException(CommerceErrorCode.FORBIDDEN);
        }
        return toResponse(group, user);
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
        return toOrderResponse(order, user);
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
        return orders.map(order -> toOrderResponse(order, user));
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> listManaged(CurrentUser user, UUID userId, UUID sellerId, OrderStatus status,
                                          OrderType orderType, OffsetDateTime from, OffsetDateTime to, int page, int size) {
        requireOrderListAccess(user, "ADMIN", "SUPPORT_STAFF");
        return findOrders(user, userId, sellerId, status, orderType, from, to, page, size);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> listSales(CurrentUser user, OrderStatus status, OffsetDateTime from,
                                        OffsetDateTime to, int page, int size) {
        requireOrderListAccess(user, "SELLER");
        return findOrders(user, null, user.id(), status, null, from, to, page, size);
    }

    private void requireOrderListAccess(CurrentUser user, String... roles) {
        if (user == null || user.id() == null) {
            throw new DomainException(CommerceErrorCode.UNAUTHENTICATED);
        }
        if (!user.hasPrivilege("ORDER.LIST") || Arrays.stream(roles).noneMatch(user::hasRole)) {
            throw new DomainException(CommerceErrorCode.FORBIDDEN);
        }
    }

    private Page<OrderResponse> findOrders(CurrentUser user, UUID userId, UUID sellerId, OrderStatus status, OrderType orderType,
                                           OffsetDateTime from, OffsetDateTime to, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new DomainException(CommerceErrorCode.INVALID_ORDER_FILTER,
                    "page must be 0 or more and size between 1 and 100");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new DomainException(CommerceErrorCode.INVALID_ORDER_FILTER, "from must not be after to");
        }
        Specification<Order> filter = (root, query, criteria) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (userId != null) predicates.add(criteria.equal(root.get("userId"), userId));
            if (sellerId != null) predicates.add(criteria.equal(root.get("sellerId"), sellerId));
            if (status != null) predicates.add(criteria.equal(root.get("status"), status));
            if (orderType != null) predicates.add(criteria.equal(root.get("orderType"), orderType));
            if (from != null) predicates.add(criteria.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) predicates.add(criteria.lessThanOrEqualTo(root.get("createdAt"), to));
            return criteria.and(predicates.toArray(Predicate[]::new));
        };
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "orderNumber"));
        return orderRepository.findAll(filter, pageable).map(order -> toOrderResponse(order, user));
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
                .map(group -> toResponse(group, user))
                .toList();
    }

    private OrderGroupResponse toResponse(OrderGroup group, CurrentUser user) {
        List<OrderResponse> orders = group.getOrders().stream()
                .sorted(Comparator.comparing(Order::getOrderNumber))
                .map(order -> toOrderResponse(order, user))
                .toList();
        return new OrderGroupResponse(group.getId(), group.getStatus(), group.getTotalAmount(),
                group.getCurrency(), group.getDueAt(), orders);
    }

    private OrderResponse toOrderResponse(Order order, CurrentUser user) {
        List<OrderItemResponse> items = order.getItems().stream()
                .sorted(Comparator.comparing(OrderItem::getId))
                .map(this::toItemResponse)
                .toList();
        List<VoucherCheckoutService.OrderVoucher> orderVouchers = order.getDiscountAmount().signum() == 0
                ? List.of() : vouchers.summary(order.getId());
        return new OrderResponse(order.getId(), order.getOrderNumber(), order.getUserId(), order.getSellerId(),
                order.getOrderType(), order.getStatus(), order.getCreatedAt(), order.getTotalAmount(), items,
                shippingSnapshotText(order, "name", "recipientName"), shippingSnapshotText(order, "phone", "phoneNumber"),
                order.getOrderGroup().getId(), order.getSubtotal(), order.getShippingFee(),
                order.getDiscountAmount(), order.getTaxAmount(), canCancel(order, user), order.getPaymentNote(), orderVouchers);
    }

    // This is a snapshot for the UI; cancel() rechecks the locked state and may still return 409.
    private boolean canCancel(Order order, CurrentUser user) {
        return user.hasPrivilege("ORDER.CANCEL")
                && (order.getUserId().equals(user.id()) || user.hasRole("ADMIN") || user.hasRole("SUPPORT_STAFF"))
                && (order.getStatus() == OrderStatus.PAID
                    || (order.getStatus() == OrderStatus.PENDING && "PENDING".equals(order.getOrderGroup().getStatus())));
    }

    // Read the immutable order snapshot, never current user-profile data. Old orders
    // can predate the address shape; malformed/missing values remain unavailable.
    private String shippingSnapshotText(Order order, String field, String legacyField) {
        Map<String, Object> shipping = order.getShippingInfo();
        if (shipping == null) return null;
        Object value;
        if (shipping.containsKey("recipient")) {
            if (!(shipping.get("recipient") instanceof Map<?, ?> recipient)) return null;
            value = recipient.get(field);
        } else {
            if (!(shipping.get("shippingAddress") instanceof Map<?, ?> address)) return null;
            value = address.get(legacyField);
        }
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
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
