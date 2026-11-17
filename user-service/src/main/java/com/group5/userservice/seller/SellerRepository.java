package com.group5.userservice.seller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface SellerRepository extends JpaRepository<Seller, UUID> {
    Page<Seller> findByStatus(SellerStatus status, Pageable pageable);
}
