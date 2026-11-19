package com.group5.inventoryservice.warehouse.repository;

import com.group5.inventoryservice.warehouse.entity.Warehouse;
import com.group5.inventoryservice.warehouse.enums.WarehouseStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {

    boolean existsByCode(String code);

    Optional<Warehouse> findByCode(String code);

    List<Warehouse> findAllByStatus(WarehouseStatus status, Sort sort);

    // Khóa đọc giữ trạng thái kho ổn định trong suốt transaction intake.
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select warehouse from Warehouse warehouse where warehouse.id = :id")
    Optional<Warehouse> findByIdForIntake(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select warehouse from Warehouse warehouse where warehouse.code = :code")
    Optional<Warehouse> findByCodeForIntake(@Param("code") String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select warehouse from Warehouse warehouse where warehouse.id = :id")
    Optional<Warehouse> findByIdForUpdate(@Param("id") UUID id);

    Optional<Warehouse> findBySellerId(UUID sellerId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select warehouse from Warehouse warehouse where warehouse.sellerId = :sellerId")
    Optional<Warehouse> findBySellerIdForIntake(@Param("sellerId") UUID sellerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select warehouse from Warehouse warehouse where warehouse.sellerId = :sellerId")
    Optional<Warehouse> findBySellerIdForUpdate(@Param("sellerId") UUID sellerId);
}
