package com.group5.userservice.iam;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.shared.exception.UserErrorCode;
import com.group5.userservice.iam.dto.RegisterRequest;
import com.group5.userservice.iam.dto.UpdateProfileRequest;
import com.group5.userservice.iam.dto.UserListRequest;
import com.group5.userservice.iam.dto.UserResponse;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.Gender;
import com.group5.userservice.shared.config.IamProperties;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final IamProperties iamProperties;
    private final KeycloakAdminClient keycloak;
    private final TransactionTemplate transaction;

    public UserService(UserRepository userRepository, IamProperties iamProperties,
                       KeycloakAdminClient keycloak, PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.iamProperties = iamProperties;
        this.keycloak = keycloak;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public UserResponse register(RegisterRequest request) {
        if (!iamProperties.isAllowSelfRegistration()) {
            throw new DomainException(UserErrorCode.USER_REGISTRATION_DISABLED);
        }
        String roleCode = iamProperties.getDefaultUserRole();
        KeycloakAdminClient.RealmRole role = keycloak.realmRole(roleCode)
                .orElseThrow(() -> new DomainException(UserErrorCode.USER_REGISTRATION_ROLE_UNAVAILABLE));
        transaction.executeWithoutResult(status -> rejectDuplicates(request));
        UUID userId = keycloak.createUser(request.email(), request.password(), request.fullName());
        try {
            keycloak.assignRealmRole(userId, role);
            return transaction.execute(status -> createUser(userId, request, roleCode));
        } catch (RuntimeException exception) {
            // execute() has already rolled back, including failures at commit.
            String field = duplicateField(exception);
            RuntimeException failure = field == null ? exception : duplicate(field);
            compensate(() -> keycloak.deleteUser(userId), failure);
            throw failure;
        }
    }

    public UserResponse getOwnProfile(UUID userId) {
        return getUser(userId);
    }

    public UserResponse getUser(UUID userId) {
        User user = transaction.execute(status -> userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new DomainException(UserErrorCode.USER_NOT_FOUND)));
        return UserResponse.from(user, keycloak.realmRoles(userId));
    }

    public Page<UserResponse> listUsers(UserListRequest request) {
        Specification<User> filter = UserSpecifications.notDeleted()
                .and(UserSpecifications.matches(request.q()))
                .and(UserSpecifications.hasStatus(request.status()))
                .and(withRole(request.role()));
        Page<User> users = transaction.execute(status -> userRepository.findAll(filter, request.pageable()));
        return users.map(user -> UserResponse.from(user, keycloak.realmRoles(user.getId())));
    }

    private Specification<User> withRole(String role) {
        if (role == null || role.isBlank()) {
            return Specification.unrestricted();
        }
        return UserSpecifications.idIn(keycloak.userIdsWithRole(role.trim().toUpperCase(Locale.ROOT)));
    }

    public UserResponse updateOwnProfile(UUID userId, UpdateProfileRequest request) {
        AtomicReference<String> replacedEmail = new AtomicReference<>();
        User user;
        try {
            user = transaction.execute(status -> updateProfileInTransaction(userId, request, replacedEmail));
        } catch (RuntimeException exception) {
            // The transaction has already rolled back before translating a database conflict.
            RuntimeException failure = profileFailure(exception);
            String previousEmail = replacedEmail.get();
            if (previousEmail != null) {
                compensate(() -> keycloak.updateEmail(userId, previousEmail), failure);
            }
            throw failure;
        }
        return UserResponse.from(user, keycloak.realmRoles(userId));
    }

    private static RuntimeException profileFailure(RuntimeException exception) {
        String field = duplicateField(exception);
        if (field != null) {
            return duplicate(field);
        }
        if (isProfileUniqueViolation(exception)) {
            return new DomainException(UserErrorCode.USER_PROFILE_CONFLICT);
        }
        return exception;
    }

    private static void compensate(Runnable undo, RuntimeException failure) {
        try {
            undo.run();
        } catch (RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private User updateProfileInTransaction(UUID userId, UpdateProfileRequest request,
                                            AtomicReference<String> replacedEmail) {
        User user = userRepository.findForProfileUpdate(userId)
                .orElseThrow(() -> new DomainException(UserErrorCode.USER_NOT_FOUND));
        String previousEmail = user.getEmail();

        if (request.email() != null
                && userRepository.existsByEmailAndIdNot(request.email(), userId)) {
            throw duplicate("email");
        }

        if (request.fullName() != null) {
            user.setFullName(request.fullName());
        }
        if (request.dateOfBirth() != null) {
            user.setDateOfBirth(request.dateOfBirth());
        }
        if (request.gender() != null) {
            user.setGender(request.gender());
        }
        if (request.address() != null) {
            user.setAddress(request.address());
        }
        if (request.email() != null) {
            user.setEmail(request.email());
        }

        User saved = userRepository.saveAndFlush(user);
        if (request.email() != null && !request.email().equals(previousEmail)) {
            keycloak.updateEmail(userId, request.email());
            replacedEmail.set(previousEmail);
        }
        return saved;
    }

    private static boolean isProfileUniqueViolation(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private void rejectDuplicates(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw duplicate("email");
        }
        if (userRepository.existsByPhone(request.phone())) {
            throw duplicate("phone");
        }
        if (userRepository.existsByIdentityNumber(request.identityNumber())) {
            throw duplicate("identityNumber");
        }
    }

    private UserResponse createUser(UUID userId, RegisterRequest request, String roleCode) {
        userRepository.lockRegistrationIdentity(request.identityNumber());
        rejectDuplicates(request);

        User user = new User();
        user.setId(userId);
        user.setEmail(request.email().toLowerCase());
        user.setPhone(request.phone());
        user.setFullName(request.fullName());
        user.setIdentityNumber(request.identityNumber());
        user.setGender(Gender.valueOf(request.gender()));
        user.setAddress(request.address());
        user.setDateOfBirth(LocalDate.parse(request.dateOfBirth()));

        return UserResponse.from(userRepository.saveAndFlush(user), List.of(roleCode));
    }

    private static DomainException duplicate(String field) {
        UserErrorCode code = switch (field) {
            case "email" -> UserErrorCode.USER_EMAIL_ALREADY_USED;
            case "phone" -> UserErrorCode.USER_PHONE_ALREADY_USED;
            case "identityNumber" -> UserErrorCode.USER_IDENTITY_NUMBER_ALREADY_USED;
            default -> throw new IllegalArgumentException("Unknown duplicate field: " + field);
        };
        return new DomainException(code);
    }

    private static String duplicateField(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())
                    && violation.getConstraintName() != null) {
                return switch (violation.getConstraintName()) {
                    case "users_email_key" -> "email";
                    case "users_phone_key" -> "phone";
                    default -> null;
                };
            }
        }
        return null;
    }
}
