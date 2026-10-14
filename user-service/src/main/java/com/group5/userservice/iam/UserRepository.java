package com.group5.userservice.iam;

import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    boolean existsByIdentityNumber(String identityNumber);

    @Query("select r from Role r where r.code = :code and r.deletedAt is null")
    Optional<Role> findRegistrationRole(@Param("code") String code);

    // The existing schema has no identity-number UNIQUE constraint. Serialize
    // this registration path across instances without altering database schema.
    @Query(value = """
            select 1 from pg_advisory_xact_lock(
                hashtextextended('nexus:registration:identity:' || :identityNumber, 0))
            """, nativeQuery = true)
    Integer lockRegistrationIdentity(@Param("identityNumber") String identityNumber);

    @Query("""
            select r.code as role, p.code as permission
            from User u
            join u.roles r
            left join r.permissions p
            where u.id = :userId
              and r.deletedAt is null
            """)
    //đã test và thấy gọn nhất rồi
    List<RoleGrant> findRoleGrants(@Param("userId") UUID userId);

    interface RoleGrant {

        String getRole();

        String getPermission();
    }
}
