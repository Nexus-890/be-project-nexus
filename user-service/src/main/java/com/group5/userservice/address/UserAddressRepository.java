package com.group5.userservice.address;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserAddressRepository extends JpaRepository<UserAddress, UUID> {

    List<UserAddress> findByUserIdOrderByDefaultAddressDescCreatedAtAscIdAsc(UUID userId);

    Optional<UserAddress> findByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);

    Optional<UserAddress> findFirstByUserIdOrderByUpdatedAtDescIdDesc(UUID userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE user_addresses
            SET is_default = false, updated_at = now(), version = version + 1
            WHERE user_id = :userId AND is_default AND id <> :keepId
            """, nativeQuery = true)
    int clearDefault(@Param("userId") UUID userId, @Param("keepId") UUID keepId);
}
