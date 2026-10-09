package com.group5.userservice.iam;

import com.group5.common_core.constant.ErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.userservice.iam.UserRepository.RoleGrant;
import com.group5.userservice.iam.dto.LoginRequest;
import com.group5.userservice.iam.dto.LoginResponse;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.UserStatus;
import com.group5.userservice.shared.config.IamProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Slf4j
@Service
//@RequiredArgsConstructor <MUST> @PostConstruct phải được gọi và dummyPasswordHash != null
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtIssuer jwtIssuer;
    private final IamProperties iamProperties;
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtIssuer jwtIssuer,
                       IamProperties iamProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtIssuer = jwtIssuer;
        this.iamProperties = iamProperties;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public LoginResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        Optional<User> found = userRepository.findByEmailAndDeletedAtIsNull(email);
        String passwordHash = found.map(User::getPasswordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), passwordHash);

        if (found.isEmpty()) {
            log.warn("Login rejected: reason=UNKNOWN_EMAIL");
            throw invalidCredentials();
        }
        User user = found.get();
        if (!passwordMatches) {
            log.warn("Login rejected: reason=WRONG_PASSWORD, userId={}", user.getId());
            throw invalidCredentials();
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            log.warn("Login rejected: reason=NOT_ACTIVE, userId={}, status={}", user.getId(), user.getStatus());
            throw invalidCredentials();
        }

        Set<String> roles = new TreeSet<>();
        Set<String> privileges = new TreeSet<>();
        for (RoleGrant grant : userRepository.findRoleGrants(user.getId())) {
            roles.add(grant.getRole());
            if (grant.getPermission() != null) {
                privileges.add(grant.getPermission());
            }
        }

        String accessToken = jwtIssuer.issueAccessToken(user.getId(), roles, privileges);
        log.info("Login succeeded: userId={}, roles={}", user.getId(), roles);
        return LoginResponse.bearer(accessToken, iamProperties.getAccessTokenExpiryMinutes() * 60);
    }

    private DomainException invalidCredentials() {
        return new DomainException(ErrorCode.INVALID_CREDENTIALS);
    }
}