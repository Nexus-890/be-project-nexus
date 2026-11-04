package com.group5.catalogservice.product;

import com.group5.catalogservice.product.entity.ProductImage;
import com.group5.catalogservice.product.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id and p.deletedAt is null")
    Optional<Product> findProductForUpload(@Param("id") UUID id);

    List<ProductImage> findAllByProduct_IdOrderBySortOrderAscIdAsc(UUID productId);
}
