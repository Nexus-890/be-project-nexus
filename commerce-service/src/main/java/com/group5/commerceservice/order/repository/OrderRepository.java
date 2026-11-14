package com.group5.commerceservice.order.repository;

import com.group5.commerceservice.order.OrderStatus;
import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.entity.OrderItem;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    // Cancellation eligibility reads the group's status; fetch this to-one association with the page.
    @EntityGraph(attributePaths = "orderGroup")
    Page<Order> findByUserIdOrderByCreatedAtDescOrderNumberDesc(UUID userId, Pageable pageable);

    @EntityGraph(attributePaths = "orderGroup")
    Page<Order> findByUserIdAndStatusOrderByCreatedAtDescOrderNumberDesc(UUID userId, OrderStatus status,
            Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "orderGroup")
    Page<Order> findAll(Specification<Order> spec, Pageable pageable);

    @Query(value = "SELECT nextval('order_number_seq')", nativeQuery = true)
    long nextOrderSequence();

    @Query("select (count(i) > 0) from OrderItem i where i.productId = :productId")
    boolean hasProductReferences(UUID productId);

    @Query("select o.status from Order o where o.id = :id")
    Optional<OrderStatus> findStatusById(UUID id);

    @Query(value = "SELECT id FROM orders WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<UUID> lockOrder(UUID id);

    @Query(value = "SELECT id FROM orders WHERE status = 'DELIVERED' AND receipt_issue_reported_at IS NULL AND auto_complete_at <= :now ORDER BY auto_complete_at, id LIMIT 100", nativeQuery = true)
    List<UUID> findReceiptDue(java.time.OffsetDateTime now);

    @Modifying
    @Query(value = "UPDATE orders SET delivered_at = :deliveredAt, auto_complete_at = :dueAt WHERE id = :id AND status = 'DELIVERED' AND delivered_at IS NULL", nativeQuery = true)
    int recordDeliveredAt(UUID id, java.time.OffsetDateTime deliveredAt, java.time.OffsetDateTime dueAt);

    @Modifying
    @Query(value = "UPDATE orders SET receipt_issue_reason=:reason, receipt_issue_reported_at=:reportedAt, updated_at=now(), updated_by=:actor, version=version+1 WHERE id=:id AND status='DELIVERED' AND receipt_issue_reported_at IS NULL", nativeQuery = true)
    int reportReceiptIssue(UUID id, String reason, java.time.OffsetDateTime reportedAt, UUID actor);

    @Query(value = "SELECT id FROM orders WHERE order_group_id = :groupId ORDER BY id FOR UPDATE", nativeQuery = true)
    List<UUID> lockGroupOrders(UUID groupId);

    @Query(value = "SELECT last_refund_version FROM orders WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Long> lockRefundVersion(UUID id);

    @Modifying
    @Query(value = "UPDATE orders SET last_refund_version = :refundVersion WHERE id = :id", nativeQuery = true)
    int recordRefundVersion(UUID id, long refundVersion);

    // Chỉ ghi paid_at lần đầu sau khi state machine đã chuyển PAID, không ghi đè bằng chứng thanh toán cũ.
    @Modifying
    @Query(value = "UPDATE orders SET paid_at = :paidAt WHERE id = :id AND status = 'PAID' AND paid_at IS NULL",
            nativeQuery = true)
    int recordPaidAt(UUID id, java.time.OffsetDateTime paidAt);

    @Query("select o.id from Order o where o.orderGroup.id = :groupId and o.status = :status order by o.id")
    List<UUID> findIdsByGroupAndStatus(UUID groupId, OrderStatus status);

    @Query("select i from OrderItem i where i.order.id in :orderIds order by i.id")
    List<OrderItem> findItemsByOrderIds(List<UUID> orderIds);

    @Modifying
    @Query(value = """
            UPDATE orders
            SET status = CAST(:to AS order_status), version = version + 1,
                updated_at = now(), updated_by = :actor
            WHERE id = :id AND status = CAST(:from AS order_status)
            """, nativeQuery = true)
    int moveStatus(UUID id, String from, String to, UUID actor);
}
