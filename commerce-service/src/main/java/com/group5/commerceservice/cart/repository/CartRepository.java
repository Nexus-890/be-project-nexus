package com.group5.commerceservice.cart.repository;

import com.group5.commerceservice.cart.entity.Cart;
import com.group5.commerceservice.cart.CartStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CartRepository extends JpaRepository<Cart, UUID> {

    Optional<Cart> findByUserIdAndStatus(UUID userId, CartStatus status);

    // ON CONFLICT dựa trên partial unique index idx_carts_one_active
    // (user_id WHERE status='ACTIVE' AND user_id IS NOT NULL).
    // Nếu cart ACTIVE đã tồn tại → DO NOTHING; nếu chưa → INSERT.
    // Cách này an toàn với nhiều request đồng thời: chỉ đúng một row được tạo.
    @Modifying
    @Query(
            value = """
            INSERT INTO carts (id, user_id, status, created_at, updated_at, created_by, updated_by)
            VALUES (:id, :userId, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, :actorId, :actorId)
            ON CONFLICT (user_id) WHERE status = 'ACTIVE' AND user_id IS NOT NULL
            DO NOTHING
            """,
            nativeQuery = true
    )
    void createActiveCartIfAbsent(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("actorId") UUID actorId
    );

    // Cập nhật updated_at để "đánh dấu" cart còn hoạt động,
    // trả về số row bị ảnh hưởng (0 nếu cart đã không còn ACTIVE).
    @Modifying
    @Query(value = """
    UPDATE carts
       SET updated_at = CURRENT_TIMESTAMP,
           updated_by = :actorId
     WHERE id = :cartId
       AND status = 'ACTIVE'
    """, nativeQuery = true)
    int touchActiveCart(@Param("cartId") UUID cartId, @Param("actorId") UUID actorId);

    @Modifying
    @Query(value = """
            UPDATE carts
               SET status = 'EXPIRED',
                   updated_at = CURRENT_TIMESTAMP,
                   updated_by = :actorId
             WHERE status = 'ACTIVE'
               AND updated_at < (
                   CURRENT_TIMESTAMP - (:expirationDays * INTERVAL '1 day')
               )
            """, nativeQuery = true)
    int expireInactiveActiveCarts(
            @Param("expirationDays") int expirationDays,
            @Param("actorId") UUID actorId
    );
}
