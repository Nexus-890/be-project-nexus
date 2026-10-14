package com.group5.userservice.iam;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.iam.dto.RegisterRequest;
import com.group5.userservice.iam.dto.UpdateProfileRequest;
import com.group5.userservice.iam.dto.UserResponse;
import com.group5.userservice.iam.entity.Role;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.Gender;
import com.group5.userservice.shared.config.IamProperties;
import org.hibernate.exception.ConstraintViolationException;
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
    private final TransactionTemplate transaction;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       IamProperties iamProperties, PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.iamProperties = iamProperties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public UserResponse register(RegisterRequest request) {
        if (!iamProperties.isAllowSelfRegistration()) {
            throw new DomainException(403, "Self-registration is disabled");
        }
        String roleCode = iamProperties.getDefaultUserRole();
        try {
            return transaction.execute(status -> createUser(request, roleCode));
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
                    .orElseThrow(() -> new DomainException(404, "User not found"));
            return UserResponse.from(user);
        });
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
                throw new DomainException(409, "Email or phone is already used");
            }
            throw exception;
        }
    }

    private UserResponse updateProfileInTransaction(UUID userId, UpdateProfileRequest request) {
        User user = userRepository.findForProfileUpdate(userId)
                .orElseThrow(() -> new DomainException(404, "User not found"));

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

    private UserResponse createUser(RegisterRequest request, String roleCode) {
        userRepository.lockRegistrationIdentity(request.identityNumber());
        if (userRepository.existsByEmail(request.email())) {
            throw duplicate("email");
        }
        if (userRepository.existsByPhone(request.phone())) {
            throw duplicate("phone");
        }
        if (userRepository.existsByIdentityNumber(request.identityNumber())) {
            throw duplicate("identityNumber");
        }
        Role role = userRepository.findRegistrationRole(roleCode)
                .orElseThrow(() -> new DomainException(503, "Default registration role is unavailable"));

        User user = new User();
        user.setEmail(request.email());
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
        return new DomainException(409, field + " is already used");
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
