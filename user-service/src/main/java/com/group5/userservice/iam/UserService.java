package com.group5.userservice.iam;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.shared.exception.UserErrorCode;
import com.group5.userservice.iam.dto.RegisterRequest;
import com.group5.userservice.iam.dto.UpdateProfileRequest;
import com.group5.userservice.iam.dto.UserListRequest;
import com.group5.userservice.iam.dto.UserResponse;
import com.group5.userservice.iam.entity.Role;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.Gender;
import com.group5.userservice.shared.config.IamProperties;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.Page;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.sql.SQLException;
import java.util.UUID;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final IamProperties iamProperties;
    private final KeycloakAdminClient keycloak;
    private final TransactionTemplate transaction;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       IamProperties iamProperties, KeycloakAdminClient keycloak,
                       PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
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
        keycloak.assignRealmRole(userId, role);
        try {
            return transaction.execute(status -> createUser(userId, request, roleCode));
        } catch (RuntimeException exception) {
            // execute() has already rolled back, including failures at commit.
            String field = duplicateField(exception);
            if (field != null) {
                throw duplicate(field);
            }
            throw exception;
        }
    }

    public UserResponse getOwnProfile(UUID userId) {
        return getUser(userId);
    }

    public UserResponse getUser(UUID userId) {
        return transaction.execute(status -> {
            User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                    .orElseThrow(() -> new DomainException(UserErrorCode.USER_NOT_FOUND));
            return UserResponse.from(user);
        });
    }

    public Page<UserResponse> listUsers(UserListRequest request) {
        Specification<User> filter = UserSpecifications.notDeleted()
                .and(UserSpecifications.matches(request.q()))
                .and(UserSpecifications.hasStatus(request.status()))
                .and(UserSpecifications.hasRole(request.role()));
        return transaction.execute(status ->
                userRepository.findAll(filter, request.pageable()).map(UserResponse::from));
    }

    public UserResponse updateOwnProfile(UUID userId, UpdateProfileRequest request) {
        try {
            return transaction.execute(status -> updateProfileInTransaction(userId, request));
        } catch (RuntimeException exception) {
            // The transaction has already rolled back before translating a database conflict.
            String field = duplicateField(exception);
            if (field != null) {
                throw duplicate(field);
            }
            if (isProfileUniqueViolation(exception)) {
                throw new DomainException(UserErrorCode.USER_PROFILE_CONFLICT);
            }
            throw exception;
        }
    }

    private UserResponse updateProfileInTransaction(UUID userId, UpdateProfileRequest request) {
        User user = userRepository.findForProfileUpdate(userId)
                .orElseThrow(() -> new DomainException(UserErrorCode.USER_NOT_FOUND));

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

        return UserResponse.from(userRepository.saveAndFlush(user));
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
        Role role = userRepository.findRegistrationRole(roleCode)
                .orElseThrow(() -> new DomainException(UserErrorCode.USER_REGISTRATION_ROLE_UNAVAILABLE));

        User user = new User();
        user.setId(userId);
        user.setEmail(request.email().toLowerCase());
        user.setPhone(request.phone());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFullName(request.fullName());
        user.setIdentityNumber(request.identityNumber());
        user.setGender(Gender.valueOf(request.gender()));
        user.setAddress(request.address());
        user.setDateOfBirth(LocalDate.parse(request.dateOfBirth()));
        user.assignInitialRole(role);

        return UserResponse.from(userRepository.saveAndFlush(user));
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
