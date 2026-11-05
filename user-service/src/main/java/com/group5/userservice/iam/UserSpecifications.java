package com.group5.userservice.iam;

import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.UserStatus;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;
import java.util.Set;
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

    static Specification<User> idIn(Set<UUID> ids) {
        if (ids.isEmpty()) {
            return (root, query, cb) -> cb.disjunction();
        }
        return (root, query, cb) -> root.get("id").in(ids);
    }
}
