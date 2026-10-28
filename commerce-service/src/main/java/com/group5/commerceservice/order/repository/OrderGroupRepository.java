package com.group5.commerceservice.order.repository;

import com.group5.commerceservice.order.entity.OrderGroup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderGroupRepository extends JpaRepository<OrderGroup, UUID> {
    Optional<OrderGroup> findByIdempotencyKey(String idempotencyKey);

    // Khóa được giữ đến cuối transaction, kể cả khi chạy nhiều instance.
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key, 0))", nativeQuery = true)
    Integer lockKey(@Param("key") String key);

    List<OrderGroup> findByBuyerIdOrderByCreatedAtDesc(UUID buyerId);
}
