package com.group5.userservice.iam;

import com.group5.userservice.iam.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailAndDeletedAtIsNull(String email);

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