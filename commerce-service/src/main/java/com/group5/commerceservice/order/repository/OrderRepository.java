package com.group5.commerceservice.order.repository;

import com.group5.commerceservice.order.entity.Order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Query(value = "SELECT nextval('order_number_seq')", nativeQuery = true)
    long nextOrderSequence();

    @Query("select (count(i) > 0) from OrderItem i where i.productId = :productId")
    boolean hasProductReferences(UUID productId);
}
