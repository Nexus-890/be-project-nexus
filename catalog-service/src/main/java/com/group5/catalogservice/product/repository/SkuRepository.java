package com.group5.catalogservice.product.repository;

import com.group5.catalogservice.product.entity.Skus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SkuRepository extends JpaRepository<Skus, UUID> {

    List<Skus> findByProduct_IdAndStatus(UUID productId, String status);

    Optional<Skus> findByIdAndStatus(UUID id, String status);
}