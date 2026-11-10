package com.group5.catalogservice.product.repository;

import com.group5.catalogservice.product.entity.Skus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SkuRepository extends JpaRepository<Skus, UUID> {
    List<Skus> findAllByProduct_Id(UUID productId);

    @Query("select s.skuCode from Skus s where s.product.id = :productId and s.skuCode like :pattern")
    List<String> findSkuCodes(@Param("productId") UUID productId, @Param("pattern") String pattern);
}
