package com.group5.userservice.iam;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.iam.dto.RegisterRequest;
import com.group5.userservice.iam.dto.UserResponse;
import com.group5.userservice.iam.entity.Role;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.Gender;
import jakarta.validation.Valid;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;
    private final TransactionTemplate transaction;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       Environment environment, PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public UserResponse register(RegisterRequest request) {
        // Read the refreshed Environment on every request, not a startup snapshot.
        if (!environment.getProperty("nexus.iam.allow-self-registration", Boolean.class, true)) {
            throw new DomainException(403, "Self-registration is disabled");
        }
        String roleCode = environment.getProperty("nexus.iam.default-user-role", "BUYER");
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

    // Kept here to honor the issue's five-production-file boundary.
    @RestController
    @RequestMapping("/api/v1/auth")
    public static class RegistrationController {
        private final UserService userService;

        public RegistrationController(UserService userService) {
            this.userService = userService;
        }

        @PostMapping("/register")
        public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .cacheControl(CacheControl.noStore())
                    .body(userService.register(request));
        }
    }
}
