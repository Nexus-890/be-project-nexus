package com.group5.commerceservice.order;

import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.entity.OrderGroup;
import com.group5.commerceservice.order.event.CheckoutPaymentConfirmed;
import com.group5.commerceservice.order.event.OrderEventPublisher;
import com.group5.commerceservice.order.event.OrderPaid;
import com.group5.commerceservice.order.repository.OrderGroupRepository;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.service.OrderStateMachine;
import com.group5.common_events.EventEnvelope;
import com.group5.common_outbox.IdempotentConsumer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Xác nhận thanh toán cả nhóm đơn, ghi lịch sử và OrderPaid cùng giao dịch với dấu chống xử lý lặp. */
@Service
@RequiredArgsConstructor
public class CheckoutPaymentConfirmedConsumer {
    public static final String CONSUMER = "commerce-checkout-payment-confirmed";
    private static final UUID SYSTEM = new UUID(0, 0);

    private final IdempotentConsumer consumer;
    private final OrderGroupRepository groups;
    private final OrderRepository orders;
    private final OrderStateMachine stateMachine;
    private final OrderEventPublisher events;
    private final EntityManager entityManager;
    private final Validator validator;
    private final JsonMapper mapper;
    private final Tracer tracer;

    /** Kiểm tra contract và định tuyến Kafka trước khi IdempotentConsumer nhận quyền xử lý event. */
    public void receive(String key, EventEnvelope<CheckoutPaymentConfirmed> event) {
        require(event != null && event.payload() != null, "Missing checkout confirmation");
        CheckoutPaymentConfirmed payment = event.payload();
        require(validator.validate(payment).isEmpty(), "Invalid CheckoutPaymentConfirmed payload");
        require("CheckoutPaymentConfirmed".equals(event.eventType()), "Unexpected payment event type");
        require(payment.sessionId().toString().equals(event.aggregateId()), "Session and aggregate must match");
        require(payment.orderGroupId().toString().equals(key), "Group and Kafka key must match");
        Instant.parse(payment.paidAt());
        // Dùng trace hiện tại để các event OrderPaid nối tiếp cùng luồng xử lý thanh toán.
        if (tracer.currentSpan() != null) {
            consumer.handle(CONSUMER, event, ignored -> apply(payment));
            return;
        }
        // Khi Kafka observation bị tắt, vẫn tạo phạm vi trace để outbox sinh envelope hợp lệ.
        Span span = tracer.nextSpan().name("checkout-payment-confirmed").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            consumer.handle(CONSUMER, event, envelope -> apply(payment));
        } finally {
            span.end();
        }
    }

    /** Mọi cập nhật nhóm đơn, đơn con, lịch sử và outbox đều thuộc transaction của IdempotentConsumer. */
    private void apply(CheckoutPaymentConfirmed payment) {
        // Khóa cùng thứ tự với luồng hủy: nhóm trước, rồi các đơn con theo UUID để tránh deadlock.
        String status = groups.lockStatus(payment.orderGroupId())
                .orElseThrow(() -> new IllegalStateException("Confirmation for an unknown order group"));
        orders.lockGroupOrders(payment.orderGroupId());
        OrderGroup group = groups.findById(payment.orderGroupId()).orElseThrow();
        List<Order> children = group.getOrders().stream().sorted(Comparator.comparing(Order::getId)).toList();
        Map<UUID, CheckoutPaymentConfirmed.Instruction> shares = validatePlan(group, children, payment);
        OffsetDateTime paidAt = Instant.parse(payment.paidAt()).truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);

        // Nhóm đã thanh toán chỉ chấp nhận lại đúng session, receipt và thời điểm đã lưu.
        if ("PAID".equals(status)) {
            require(payment.sessionId().equals(group.getPaymentSessionId())
                            && payment.transactionId().equals(group.getPaymentTransactionId())
                            && group.getPaidAt() != null && paidAt.isEqual(group.getPaidAt()),
                    "Paid group is bound to a different payment receipt");
            // EventId mới cho cùng receipt cũng không tạo thêm lịch sử hoặc lùi trạng thái giao hàng/hủy.
            return;
        }
        if ("CANCELED".equals(status) || "EXPIRED".equals(status)) {
            // Payment giữ bằng chứng thu tiền và xử lý hoàn tiền; Commerce không mở lại nhóm đã đóng.
            return;
        }
        require("PENDING".equals(status), "Group is not payable");
        require(group.getDueAt() == null || !paidAt.isAfter(group.getDueAt()), "Funding occurred after the group deadline");

        // Gắn danh tính thanh toán bền vững để nhận diện receipt cũ ngay cả khi envelope có eventId mới.
        group.setStatus("PAID");
        group.setPaidAt(paidAt);
        group.setPaymentSessionId(payment.sessionId());
        group.setPaymentTransactionId(payment.transactionId());
        group.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        group.setUpdatedBy(SYSTEM);
        groups.saveAndFlush(group);

        // Chỉ chuyển các đơn còn PENDING; state machine chịu trách nhiệm kiểm tra bước chuyển và ghi lịch sử.
        for (Order order : children) {
            if (order.getStatus() != OrderStatus.PENDING) continue;
            stateMachine.move(order.getId(), OrderStatus.PAID, SYSTEM, "Checkout payment confirmed");
            require(orders.recordPaidAt(order.getId(), paidAt) == 1, "Order already has payment evidence");
            entityManager.flush();
            // State machine dùng native SQL: đọc lại entity để event chứa đúng version và paid_at đã lưu.
            entityManager.refresh(order);
            OrderPaid payload = snapshot(order, shares.get(order.getId()), payment);
            // Snapshot thiếu dữ liệu làm rollback toàn nhóm, không phát OrderPaid không đúng schema.
            require(validator.validate(payload).isEmpty(), "Order has an incomplete shipping snapshot");
            events.orderPaid(payload);
        }
    }

    /** Đối chiếu toàn bộ kế hoạch thanh toán với dữ liệu Commerce, không chấp nhận trả tiền một phần nhóm. */
    private Map<UUID, CheckoutPaymentConfirmed.Instruction> validatePlan(
            OrderGroup group, List<Order> children, CheckoutPaymentConfirmed payment) {
        require(group.getBuyerId().equals(payment.buyerId()), "Payment buyer does not own the group");
        require(group.getCurrency().equals(payment.currency()), "Payment currency does not match the group");
        require(new BigDecimal(payment.totalAmount()).signum() > 0
                        && group.getTotalAmount().compareTo(new BigDecimal(payment.totalAmount())) == 0,
                "Payment total does not match the group");
        // Cả instructionId và orderId phải duy nhất; mỗi đơn chỉ được nhận đúng một phần tiền.
        Map<UUID, CheckoutPaymentConfirmed.Instruction> shares = new HashMap<>();
        var instructionIds = new HashSet<UUID>();
        BigDecimal sum = BigDecimal.ZERO;
        for (var share : payment.instructions()) {
            require(shares.putIfAbsent(share.orderId(), share) == null
                            && instructionIds.add(share.instructionId()), "Duplicate payment instruction or order");
            sum = sum.add(new BigDecimal(share.amount()));
        }
        require(!children.isEmpty() && children.size() == shares.size(), "Payment must contain every group order");
        // Kiểm tra đúng tập đơn, đúng người mua và đúng số tiền từng đơn, kể cả khi tổng vẫn bằng nhau.
        BigDecimal orderTotal = BigDecimal.ZERO;
        for (Order order : children) {
            var share = shares.get(order.getId());
            require(share != null, "Payment order does not belong to the group");
            require(order.getUserId().equals(group.getBuyerId()), "Order buyer does not match the group");
            require(order.getTotalAmount().compareTo(new BigDecimal(share.amount())) == 0,
                    "Instruction amount does not match the order");
            orderTotal = orderTotal.add(order.getTotalAmount());
        }
        require(sum.compareTo(group.getTotalAmount()) == 0 && orderTotal.compareTo(group.getTotalAmount()) == 0,
                "Payment shares do not sum to the group total");
        return shares;
    }

    /** Dựng OrderPaid từ snapshot đơn và order_items, không đọc lại dịch vụ khác hoặc thay đổi tồn kho. */
    private OrderPaid snapshot(Order order, CheckoutPaymentConfirmed.Instruction instruction,
                               CheckoutPaymentConfirmed payment) {
        Map<String, Object> stored = order.getShippingInfo();
        require(stored != null && stored.get("warehouseId") != null, "Order has no stored warehouse");
        require(!order.getUserId().equals(order.getSellerId()), "Buyer and seller must differ");
        require(order.getOrderType() == OrderType.AUCTION ? order.getReferenceId() != null : order.getReferenceId() == null,
                "Order type and reference must match");
        // Người nhận/kho lấy từ shipping_info; danh sách hàng lấy từ order_items đã chốt lúc tạo đơn.
        // Parcel và địa chỉ lấy hàng giữ null nếu snapshot chưa có, không tự bịa thông tin vận chuyển.
        var shipping = new OrderPaid.ShippingInfo(mapper.convertValue(stored.get("recipient"), OrderPaid.Recipient.class),
                order.getItems().stream().map(item -> new OrderPaid.Item(item.getSkuId(), item.getProductName(),
                        item.getQuantity())).toList(),
                mapper.convertValue(stored.get("parcel"), OrderPaid.Parcel.class),
                mapper.convertValue(stored.get("pickupAddress"), OrderPaid.Address.class));
        return new OrderPaid(order.getId(), order.getUserId(), order.getOrderNumber(), order.getOrderType(),
                order.getReferenceId(), order.getPaidAt().toInstant().toString(), order.getOrderGroup().getId(),
                order.getSellerId(), order.getVersion(), payment.sessionId(), instruction.instructionId(),
                payment.transactionId(), money(order.getTotalAmount()), payment.currency(),
                UUID.fromString(stored.get("warehouseId").toString()), money(order.getShippingFee()), shipping);
    }

    /** Event dùng chuỗi tiền đúng hai chữ số thập phân; dữ liệu sai độ chính xác phải báo lỗi. */
    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
