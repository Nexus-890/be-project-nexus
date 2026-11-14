package com.group5.userservice.iam;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.iam.dto.AdminCreateUserRequest;
import com.group5.userservice.iam.dto.UpdateProfileRequest;
import com.group5.userservice.iam.dto.UserResponse;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.Gender;
import com.group5.userservice.iam.enums.UserStatus;
import com.group5.userservice.shared.config.IamProperties;
import com.group5.userservice.shared.exception.UserErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Service
public class AdminUserService {
    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);
    private final UserRepository users;
    private final KeycloakAdminClient keycloak;
    private final UserEventPublisher events;
    private final IamProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;

    /** Tạo transaction độc lập để bắt được cả lỗi commit trước khi thực hiện hoàn tác Keycloak. */
    public AdminUserService(UserRepository users, KeycloakAdminClient keycloak, UserEventPublisher events,
                            IamProperties properties, Clock clock, PlatformTransactionManager manager) {
        this.users = users;
        this.keycloak = keycloak;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Tạo profile và identity với cùng UUID, gán bộ role cố định rồi ghi hai audit event. */
    public UserResponse create(AdminCreateUserRequest request, UUID actor) {
        var profile = request.profile();
        List<KeycloakAdminClient.RealmRole> roles = resolveRoles(request.roleCodes());
        return coordinated(undo -> transaction.execute(status -> {
            users.lockRegistrationIdentity(profile.identityNumber());
            if (users.existsByEmail(profile.email())) throw new DomainException(UserErrorCode.USER_EMAIL_ALREADY_USED);
            if (users.existsByPhone(profile.phone())) throw new DomainException(UserErrorCode.USER_PHONE_ALREADY_USED);
            if (users.existsByIdentityNumber(profile.identityNumber()))
                throw new DomainException(UserErrorCode.USER_IDENTITY_NUMBER_ALREADY_USED);
            UUID id = keycloak.createUser(profile.email(), profile.password(), profile.fullName());
            undo.push(() -> keycloak.deleteUser(id));
            keycloak.replaceRealmRoles(id, roles);
            User user = new User();
            user.setId(id);
            user.setEmail(profile.email());
            user.setPhone(profile.phone());
            user.setFullName(profile.fullName());
            user.setIdentityNumber(profile.identityNumber());
            user.setGender(Gender.valueOf(profile.gender()));
            user.setDateOfBirth(LocalDate.parse(profile.dateOfBirth()));
            user.setAddress(profile.address());
            users.saveAndFlush(user);
            List<String> names = roleNames(roles);
            events.publish("UserRegistered", id, actor, Map.of("roles", names));
            events.publish("UserRoleAssigned", id, actor, Map.of("previousRoles", List.of(), "roles", names));
            return UserResponse.from(user, names);
        }));
    }

    /** Cập nhật đúng whitelist profile, đồng bộ Keycloak và chỉ audit tên trường thực sự đổi. */
    public UserResponse update(UUID id, UpdateProfileRequest request, UUID actor) {
        return coordinated(undo -> transaction.execute(status -> {
            User user = lock(id);
            List<String> changed = new ArrayList<>();
            if (request.email() != null && !request.email().equals(user.getEmail())) {
                if (users.existsByEmailAndIdNot(request.email(), id)) throw new DomainException(UserErrorCode.USER_EMAIL_ALREADY_USED);
                user.setEmail(request.email()); changed.add("email");
            }
            if (request.fullName() != null && !request.fullName().equals(user.getFullName())) {
                user.setFullName(request.fullName()); changed.add("fullName");
            }
            if (request.gender() != null && request.gender() != user.getGender()) {
                user.setGender(request.gender()); changed.add("gender");
            }
            if (request.dateOfBirth() != null && !request.dateOfBirth().equals(user.getDateOfBirth())) {
                user.setDateOfBirth(request.dateOfBirth()); changed.add("dateOfBirth");
            }
            if (request.address() != null && !request.address().equals(user.getAddress())) {
                user.setAddress(request.address()); changed.add("address");
            }
            List<String> roles = keycloak.realmRoles(id);
            if (changed.isEmpty()) return UserResponse.from(user, roles);
            users.saveAndFlush(user);
            if (changed.contains("email") || changed.contains("fullName")) {
                Map<String, Object> before = keycloak.userState(id);
                undo.push(() -> keycloak.restoreUser(id, before));
                keycloak.updateUser(id, user.getEmail(), user.getFullName(), !Boolean.FALSE.equals(before.get("enabled")));
            }
            events.publish("UserUpdated", id, actor, Map.of("changedFields", List.copyOf(changed)));
            return UserResponse.from(user, roles);
        }));
    }

    /** Xóa mềm hoặc cứng profile theo policy, luôn vô hiệu identity để chặn đăng nhập Keycloak. */
    public void delete(UUID id, UUID actor) {
        coordinated(undo -> transaction.execute(status -> {
            User user = lock(id);
            Map<String, Object> before = keycloak.userState(id);
            undo.push(() -> keycloak.restoreUser(id, before));
            keycloak.updateUser(id, user.getEmail(), user.getFullName(), false);
            boolean soft = properties.isUserSoftDelete();
            if (soft) {
                user.setStatus(UserStatus.DELETED);
                user.setDeletedAt(OffsetDateTime.now(clock));
                users.saveAndFlush(user);
            } else {
                users.delete(user);
                users.flush();
            }
            events.publish("UserDeleted", id, actor, Map.of("deletionMode", soft ? "SOFT" : "HARD"));
            return null;
        }));
    }

    /** Gán lại toàn bộ role nghiệp vụ và hoàn tác bộ role cũ nếu DB hoặc provider lỗi. */
    public UserResponse assignRoles(UUID id, List<String> codes, UUID actor) {
        List<KeycloakAdminClient.RealmRole> roles = resolveRoles(codes);
        return coordinated(undo -> transaction.execute(status -> {
            User user = lock(id);
            List<KeycloakAdminClient.RealmRole> previous = keycloak.assignedRealmRoles(id).stream()
                    .filter(role -> KeycloakAdminClient.APPLICATION_ROLES.contains(role.name())).toList();
            if (roleNames(previous).equals(roleNames(roles))) return UserResponse.from(user, roleNames(roles));
            undo.push(() -> keycloak.replaceRealmRoles(id, previous));
            keycloak.replaceRealmRoles(id, roles);
            events.publish("UserRoleAssigned", id, actor, Map.of("previousRoles", roleNames(previous), "roles", roleNames(roles)));
            return UserResponse.from(user, roleNames(roles));
        }));
    }

    /** Flush profile và audit trước khi yêu cầu Keycloak đổi credential không thể đọc lại để hoàn tác. */
    public void changePassword(UUID id, String password, UUID actor) {
        transaction.executeWithoutResult(status -> {
            lock(id);
            keycloak.userState(id);
            events.publish("UserPasswordChanged", id, actor, Map.of());
            users.flush();
            // Admin API không có XA/khôi phục credential cũ; lỗi commit sau lời gọi vẫn là giới hạn phân tán.
            keycloak.resetPassword(id, password);
        });
    }

    /** Mở khóa brute-force thực tế; khóa local chỉ được gỡ khi detector xác nhận đang khóa. */
    public void unlock(UUID id, UUID actor) {
        transaction.executeWithoutResult(status -> {
            User user = lock(id);
            keycloak.userState(id);
            if (!keycloak.isBruteForceLocked(id)) throw new DomainException(UserErrorCode.USER_ACCOUNT_NOT_LOCKED);
            if (user.getStatus() == UserStatus.LOCKED) user.setStatus(UserStatus.ACTIVE);
            users.saveAndFlush(user);
            events.publish("UserUnlocked", id, actor, Map.of());
            users.flush();
            // Keycloak không cung cấp API khôi phục bộ đếm brute-force; gọi sau toàn bộ flush local.
            keycloak.unlock(id);
        });
    }

    /** Khóa profile còn hiệu lực để tuần tự hóa các thay đổi quản trị trên cùng tài khoản. */
    private User lock(UUID id) {
        return users.findForProfileUpdate(id).orElseThrow(() -> new DomainException(UserErrorCode.USER_NOT_FOUND));
    }

    /** Kiểm tra toàn bộ role trước khi tạo/chỉnh identity, không chấp nhận role hệ thống hoặc bộ rỗng. */
    private List<KeycloakAdminClient.RealmRole> resolveRoles(List<String> codes) {
        if (codes == null || codes.isEmpty() || codes.stream().anyMatch(code -> code == null || !KeycloakAdminClient.APPLICATION_ROLES.contains(code)))
            throw new DomainException(UserErrorCode.USER_ROLE_INVALID);
        return codes.stream().distinct().sorted().map(code -> keycloak.realmRole(code)
                .orElseThrow(() -> new DomainException(UserErrorCode.USER_ROLE_INVALID))).toList();
    }

    /** Chuẩn hóa bộ role cho response, audit và phép so sánh không phụ thuộc thứ tự. */
    private static List<String> roleNames(List<KeycloakAdminClient.RealmRole> roles) {
        return roles.stream().map(KeycloakAdminClient.RealmRole::name).distinct().sorted().toList();
    }

    /** Chạy rollback SQL trước, rồi hoàn tác các thay đổi Keycloak theo thứ tự ngược khi có lỗi. */
    private <T> T coordinated(Function<Deque<Runnable>, T> operation) {
        Deque<Runnable> undo = new ArrayDeque<>();
        try {
            return operation.apply(undo);
        } catch (RuntimeException exception) {
            while (!undo.isEmpty()) {
                try { undo.pop().run(); }
                catch (RuntimeException cleanup) {
                    exception.addSuppressed(cleanup);
                    log.error("Keycloak compensation failed; reconciliation is required", cleanup);
                }
            }
            throw translateConflict(exception);
        }
    }

    /** Đổi lỗi unique do race condition thành cùng mã 409 như kiểm tra duplicate trước khi ghi. */
    private static RuntimeException translateConflict(RuntimeException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && "23505".equals(violation.getSQLState())) {
                return new DomainException("users_email_key".equals(violation.getConstraintName())
                        ? UserErrorCode.USER_EMAIL_ALREADY_USED : "users_phone_key".equals(violation.getConstraintName())
                        ? UserErrorCode.USER_PHONE_ALREADY_USED : UserErrorCode.USER_PROFILE_CONFLICT);
            }
        }
        return exception;
    }
}
