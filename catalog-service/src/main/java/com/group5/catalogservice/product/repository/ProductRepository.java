package com.group5.catalogservice.product.repository;

import com.group5.catalogservice.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.Optional;

import com.group5.catalogservice.product.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    Page<Product> findByCategory_IdAndStatusAndDeletedAtIsNull(
            UUID categoryId,
            String status,
            Pageable pageable);

    Page<Product> findByStatusAndDeletedAtIsNull(
            String status,
            Pageable pageable);

    Page<Product> findBySellerIdAndDeletedAtIsNull(
            UUID sellerId,
            Pageable pageable);

}
