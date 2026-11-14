package com.group5.userservice.iam;

import com.group5.userservice.iam.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    boolean existsByIdentityNumber(String identityNumber);

    // The existing schema has no identity-number UNIQUE constraint. Serialize
    // this registration path across instances without altering database schema.
    @Query(value = """
            select 1 from pg_advisory_xact_lock(
                hashtextextended('nexus:registration:identity:' || :identityNumber, 0))
            """, nativeQuery = true)
    Integer lockRegistrationIdentity(@Param("identityNumber") String identityNumber);

    Optional<User> findByIdAndDeletedAtIsNull(UUID id);

    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    boolean existsByEmailAndIdNot(String email, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select u from User u
        where u.id = :userId and u.deletedAt is null
        """)
    Optional<User> findForProfileUpdate(@Param("userId") UUID userId);
}
