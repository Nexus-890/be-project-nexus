package com.group5.userservice.iam;

import com.group5.userservice.iam.entity.Role;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.UserStatus;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;
import java.util.UUID;

final class UserSpecifications {

    private UserSpecifications() {
    }

    static Specification<User> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    static Specification<User> matches(String q) {
        if (q == null || q.isBlank()) {
            return Specification.unrestricted();
        }
        String pattern = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("fullName")), pattern),
                cb.like(cb.lower(root.get("email")), pattern),
                cb.like(root.get("phone"), pattern));
    }

    // Mỗi hàm là 1 điều kiện á, param trống thì không lọc gì, unrestricted()
    static Specification<User> hasStatus(UserStatus status) {
        if (status == null) {
            return Specification.unrestricted();
        }

        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    // Lọc theo role dùng exists thay vì JOIN thẳng.
    // Join thẳng làm một user có hai role hiện hai lần,
    // và totalElements bị sai.
    static Specification<User> hasRole(String role) {
        if (role == null || role.isBlank()) {
            return Specification.unrestricted();
        }

        String code = role.trim().toUpperCase(Locale.ROOT);

        return (root, query, cb) -> {
            Subquery<UUID> withRole = query.subquery(UUID.class);
            Root<User> user = withRole.from(User.class);
            Join<User, Role> roles = user.join("roles");

            withRole.select(user.get("id"))
                    .where(cb.equal(user.get("id"), root.get("id")),
                            cb.equal(roles.get("code"), code));

            return cb.exists(withRole);
        };
    }
}
