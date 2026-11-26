package com.group5.catalogservice.product.repository;

import com.group5.catalogservice.product.entity.Product;
import com.group5.catalogservice.product.entity.Skus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SkuRepository extends JpaRepository<Skus, UUID> {
    @Query("select s from Skus s join fetch s.product where s.id in :ids")
    List<Skus> findCostsByIdIn(@Param("ids") List<UUID> ids);

    List<Skus> findAllByProduct_Id(UUID productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id and p.deletedAt is null")
    Optional<Product> findProductForSkuCreate(@Param("id") UUID id);

    @Query("select s.skuCode from Skus s where s.product.id = :productId and s.skuCode like :pattern")
    List<String> findSkuCodes(@Param("productId") UUID productId, @Param("pattern") String pattern);
}
