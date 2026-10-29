package com.group5.commerceservice.order.repository;

import com.group5.commerceservice.order.OrderStatus;
import com.group5.commerceservice.order.entity.Order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Query(value = "SELECT nextval('order_number_seq')", nativeQuery = true)
    long nextOrderSequence();

    @Query("select (count(i) > 0) from OrderItem i where i.productId = :productId")
    boolean hasProductReferences(UUID productId);

    @Query("select o.status from Order o where o.id = :id")
    Optional<OrderStatus> findStatusById(UUID id);

    @Modifying
    @Query(value = """
            UPDATE orders
            SET status = CAST(:to AS order_status), version = version + 1,
                updated_at = now(), updated_by = :actor
            WHERE id = :id AND status = CAST(:from AS order_status)
            """, nativeQuery = true)
    int moveStatus(UUID id, String from, String to, UUID actor);
}
