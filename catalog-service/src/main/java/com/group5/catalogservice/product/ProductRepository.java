package com.group5.catalogservice.product;

import org.hibernate.validator.constraints.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    Page<Product> findByCategory_IdAndStatusAndDeletedAtIsNull(
            UUID categoryId,
            String status,
            Pageable pageable
    );

    Page<Product> findBySellerIdAndDeletedAtIsNull(
            UUID sellerId,
            Pageable pageable
    );
}
