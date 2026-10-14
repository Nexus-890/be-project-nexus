package com.group5.catalogservice.product.repository;

import com.group5.catalogservice.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {
    Optional<Product> findByIdAndDeletedAtIsNull(UUID id);

    @EntityGraph(attributePaths = {"category", "skuses", "productImages"})
    Optional<Product> findByIdAndStatusAndDeletedAtIsNull(UUID id, String status);
}
