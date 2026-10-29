package com.group5.commerceservice.order.repository;

import com.group5.commerceservice.order.entity.OrderStatusHistory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, UUID> {

    List<OrderStatusHistory> findByOrderIdOrderByCreatedAtAscIdAsc(UUID orderId);
}
