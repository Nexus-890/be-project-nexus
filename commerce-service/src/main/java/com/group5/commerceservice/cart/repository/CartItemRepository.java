package com.group5.commerceservice.cart.repository;

import com.group5.commerceservice.cart.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface CartItemRepository
        extends JpaRepository<CartItem, UUID> {

    List<CartItem> findByCart_Id(UUID cartId);

    // clearAutomatically = true: buộc Hibernate xóa first-level cache sau khi
    // native upsert chạy xong, để findByCart_Id trong cùng transaction đọc
    // đúng quantity đã được cộng dồn thay vì trả về giá trị cũ từ cache.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO cart_items (
                id, cart_id, product_id, sku_id, quantity, price
            )
            VALUES (
                :id, :cartId, :productId, :skuId, :quantity, :price
            )
            ON CONFLICT (cart_id, sku_id)
            DO UPDATE SET
                quantity = cart_items.quantity + EXCLUDED.quantity,
                price    = EXCLUDED.price
            """, nativeQuery = true)
    void addOrIncreaseQuantity(
            @Param("id") UUID id,
            @Param("cartId") UUID cartId,
            @Param("productId") UUID productId,
            @Param("skuId") UUID skuId,
            @Param("quantity") Integer quantity,
            @Param("price") BigDecimal price
    );


}