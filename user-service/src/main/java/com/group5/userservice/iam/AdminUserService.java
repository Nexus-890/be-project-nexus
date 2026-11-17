package com.group5.userservice.iam;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.iam.dto.AdminCreateUserRequest;
import com.group5.userservice.iam.dto.UpdateProfileRequest;
import com.group5.userservice.iam.dto.UserResponse;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.Gender;
import com.group5.userservice.iam.enums.UserStatus;
import com.group5.userservice.seller.SellerRepository;
import com.group5.userservice.shared.config.IamProperties;
import com.group5.userservice.shared.exception.UserErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
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
    private final SellerRepository sellers;
    private final KeycloakAdminClient keycloak;
    private final UserEventPublisher events;
    private final IamProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final AdminIdentityOperationJournal journal;

    /** Tạo transaction độc lập để bắt được cả lỗi commit trước khi thực hiện hoàn tác Keycloak. */
    public AdminUserService(UserRepository users, SellerRepository sellers, KeycloakAdminClient keycloak, UserEventPublisher events,
                            IamProperties properties, Clock clock, PlatformTransactionManager manager,
                            AdminIdentityOperationJournal journal) {
        this.users = users;
        this.sellers = sellers;
        this.keycloak = keycloak;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
        this.journal = journal;
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
                // Xóa hồ sơ seller trước user để thỏa FK; cả hai cùng rollback nếu audit/commit lỗi.
                sellers.deleteById(id);
                sellers.flush();
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

    /** Flush local trước remote và giữ intent/xác nhận bền vững nếu SQL COMMIT sau đó thất bại. */
    public void changePassword(UUID id, String password, UUID actor) {
        irreversible(attempt -> transaction.executeWithoutResult(status -> {
            User user = lock(id);
            keycloak.userState(id);
            events.publish("UserPasswordChanged", id, actor, Map.of());
            users.flush();
            attempt.operationId = journal.prepare("UserPasswordChanged", id, actor, user.getVersion());
            keycloak.resetPassword(id, password);
            attempt.remoteSucceeded = true;
            journal.remoteSucceeded(attempt.operationId);
            journal.complete(attempt.operationId);
        }));
    }

    /** Mở khóa không phát lại HTTP; journal cho phép phục hồi audit/profile sau lỗi COMMIT. */
    public void unlock(UUID id, UUID actor) {
        irreversible(attempt -> transaction.executeWithoutResult(status -> {
            User user = lock(id);
            long profileVersion = user.getVersion();
            keycloak.userState(id);
            if (!keycloak.isBruteForceLocked(id)) throw new DomainException(UserErrorCode.USER_ACCOUNT_NOT_LOCKED);
            if (user.getStatus() == UserStatus.LOCKED) user.setStatus(UserStatus.ACTIVE);
            users.saveAndFlush(user);
            events.publish("UserUnlocked", id, actor, Map.of());
            users.flush();
            attempt.operationId = journal.prepare("UserUnlocked", id, actor, profileVersion);
            keycloak.unlock(id);
            attempt.remoteSucceeded = true;
            journal.remoteSucceeded(attempt.operationId);
            journal.complete(attempt.operationId);
        }));
    }

    /** Worker chỉ phục hồi remote đã xác nhận; PENDING cần người vận hành kiểm tra, không retry password. */
    @Scheduled(fixedDelayString = "${nexus.iam.reconciliation-interval:30000}")
    public void reconcileIdentityOperations() {
        for (var candidate : journal.confirmed()) {
            try {
                transaction.executeWithoutResult(status -> {
                    // Giữ user -> journal như request gốc để không deadlock khi request vẫn đang COMMIT.
                    var profile = users.findForProfileUpdate(candidate.userId());
                    var pending = journal.lockConfirmed(candidate.operationId());
                    if (pending.isEmpty()) return;
                    var operation = pending.get();
                    boolean currentProfile = profile.isPresent()
                            && profile.get().getVersion() == operation.profileVersion();
                    if (currentProfile && operation.eventType().equals("UserUnlocked")
                            && profile.get().getStatus() == UserStatus.LOCKED) {
                        profile.get().setStatus(UserStatus.ACTIVE);
                        users.saveAndFlush(profile.get());
                    }
                    events.publish(operation.eventType(), operation.userId(), operation.actorId(), Map.of(
                            "operationId", operation.operationId(), "reconciled", true,
                            "profileReconciled", currentProfile,
                            "remoteConfirmedAt", operation.remoteConfirmedAt().toString()));
                    if (currentProfile) journal.complete(operation.operationId());
                    else {
                        journal.requireReview(operation.operationId());
                        log.error("Identity operation {} requires profile review", operation.operationId());
                    }
                });
            } catch (RuntimeException failure) {
                // Không log message/body có khả năng chứa dữ liệu provider; row vẫn giữ để lần sau phục hồi.
                log.error("Identity operation {} reconciliation failed ({})", candidate.operationId(),
                        failure.getClass().getSimpleName());
            }
        }
    }

    /** Báo rõ remote đã áp dụng; journal không bị rollback cùng profile và là nguồn đối soát sau restart. */
    private void irreversible(java.util.function.Consumer<IdentityAttempt> operation) {
        IdentityAttempt attempt = new IdentityAttempt();
        try {
            operation.accept(attempt);
        } catch (RuntimeException failure) {
            if (!attempt.remoteSucceeded) throw failure;
            log.error("Remote identity operation {} succeeded; local completion requires reconciliation",
                    attempt.operationId);
            throw new DomainException(UserErrorCode.USER_IDENTITY_RECONCILIATION_REQUIRED,
                    UserErrorCode.USER_IDENTITY_RECONCILIATION_REQUIRED.message(),
                    Map.of("operationId", attempt.operationId.toString()));
        }
    }

    private static class IdentityAttempt {
        private UUID operationId;
        private boolean remoteSucceeded;
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
