package com.group5.commerceservice.cart.repository;

import com.group5.commerceservice.cart.entity.Cart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CartRepository extends JpaRepository<Cart, UUID> {

    Optional<Cart> findByUserIdAndStatus(UUID userId, String status);

    @Modifying
    @Query(
            value = """
            INSERT INTO carts (id, user_id, status, created_at, updated_at)
            VALUES (:id, :userId, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (user_id) WHERE status = 'ACTIVE' AND user_id IS NOT NULL
            DO NOTHING
            """,
            nativeQuery = true
    )
    void createActiveCartIfAbsent(
            @Param("id") UUID id,
            @Param("userId") UUID userId
    );

    @Modifying
    @Query(value = """
    UPDATE carts
       SET status = 'EXPIRED',
           updated_at = CURRENT_TIMESTAMP
     WHERE status = 'ACTIVE'
       AND updated_at < CURRENT_TIMESTAMP
           - (:expirationDays * INTERVAL '1 day')
    """, nativeQuery = true)
    int expireStaleActiveCarts(
            @Param("expirationDays") int expirationDays
    );

    @Modifying
    @Query(value = """
    UPDATE carts
       SET updated_at = CURRENT_TIMESTAMP
     WHERE id = :cartId
       AND status = 'ACTIVE'
    """, nativeQuery = true)
    int touchActiveCart(@Param("cartId") UUID cartId);
}
