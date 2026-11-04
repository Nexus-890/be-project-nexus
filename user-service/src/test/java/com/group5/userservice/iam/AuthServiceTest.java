package com.group5.userservice.iam;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.iam.dto.LoginRequest;
import com.group5.userservice.iam.dto.LoginResponse;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.UserStatus;
import com.group5.userservice.shared.config.IamProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String EMAIL = "seller@nexus.local";
    private static final String PASSWORD = "correct-password";
    private static final UUID USER_ID = UUID.fromString("64fba061-3a91-4a35-a853-221798344510");
    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-10-13T08:00:00Z");

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtIssuer jwtIssuer;

    private final PasswordEncoder passwordEncoder = spy(new BCryptPasswordEncoder(4));
    private final IamProperties iamProperties = new IamProperties();

    private AuthService authService;
    private User user;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtIssuer, iamProperties,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

        user = new User();
        user.setId(USER_ID);
        user.setEmail(EMAIL);
        user.setPasswordHash(new BCryptPasswordEncoder(4).encode(PASSWORD));
        user.setStatus(UserStatus.ACTIVE);
    }

    @Test
    void correctCredentialsReturnABearerTokenForThatUser() {
        when(userRepository.findByEmailAndDeletedAtIsNull(EMAIL)).thenReturn(Optional.of(user));
        when(userRepository.findRoleGrants(USER_ID)).thenReturn(List.of(
                new Grant("SELLER", "PRODUCT.CREATE"),
                new Grant("SELLER", "PRODUCT.UPDATE"),
                new Grant("BUYER", "PRODUCT.UPDATE"),
                new Grant("SUPPORT_STAFF", null)));
        when(jwtIssuer.issueAccessToken(
                USER_ID,
                Set.of("BUYER", "SELLER", "SUPPORT_STAFF"),
                Set.of("PRODUCT.CREATE", "PRODUCT.UPDATE")))
                .thenReturn("signed-token");

        LoginResponse response = authService.login(new LoginRequest(EMAIL, PASSWORD));

        assertThat(response.accessToken()).isEqualTo("signed-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600);
    }

    @Test
    void expiresInFollowsTheConfiguredExpiry() {
        iamProperties.setAccessTokenExpiryMinutes(15);
        when(userRepository.findByEmailAndDeletedAtIsNull(EMAIL)).thenReturn(Optional.of(user));
        when(userRepository.findRoleGrants(USER_ID)).thenReturn(List.of());
        when(jwtIssuer.issueAccessToken(USER_ID, Set.of(), Set.of())).thenReturn("signed-token");

        assertThat(authService.login(new LoginRequest(EMAIL, PASSWORD)).expiresIn()).isEqualTo(900);
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameError() {
        when(userRepository.findByEmailAndDeletedAtIsNull(EMAIL)).thenReturn(Optional.of(user));
        when(userRepository.findByEmailAndDeletedAtIsNull("nobody@nexus.local")).thenReturn(Optional.empty());

        DomainException wrongPassword = catchThrowableOfType(DomainException.class,
                () -> authService.login(new LoginRequest(EMAIL, "wrong-password")));
        DomainException unknownEmail = catchThrowableOfType(DomainException.class,
                () -> authService.login(new LoginRequest("nobody@nexus.local", PASSWORD)));

        assertThat(wrongPassword.getCode()).isEqualTo(401);
        assertThat(wrongPassword.getErrorCode()).isEqualTo("USER_INVALID_CREDENTIALS");
        assertThat(unknownEmail.getErrorCode()).isEqualTo(wrongPassword.getErrorCode());
        assertThat(unknownEmail.getCode()).isEqualTo(401);
        assertThat(unknownEmail.getMessage()).isEqualTo(wrongPassword.getMessage());
        verify(jwtIssuer, never()).issueAccessToken(any(), any(), any());
    }

    @Test
    void unknownEmailStillRunsOnePasswordComparison() {
        when(userRepository.findByEmailAndDeletedAtIsNull(EMAIL)).thenReturn(Optional.empty());

        catchThrowableOfType(DomainException.class, () -> authService.login(new LoginRequest(EMAIL, PASSWORD)));

        verify(passwordEncoder, times(1)).matches(anyString(), anyString());
        verify(userRepository, never()).findRoleGrants(any());
    }

    @Test
    void aUserWhoIsNotActiveIsRejectedEvenWithTheRightPassword() {
        user.setStatus(UserStatus.DELETED);
        when(userRepository.findByEmailAndDeletedAtIsNull(EMAIL)).thenReturn(Optional.of(user));

        DomainException rejected = catchThrowableOfType(DomainException.class,
                () -> authService.login(new LoginRequest(EMAIL, PASSWORD)));

        assertThat(rejected.getCode()).isEqualTo(401);
        verify(passwordEncoder, times(1)).matches(anyString(), anyString());
    }

    @Test
    void aPasswordLongerThanBcryptAcceptsIsRejectedNotAnError() {
        when(userRepository.findByEmailAndDeletedAtIsNull(EMAIL)).thenReturn(Optional.of(user));

        DomainException rejected = catchThrowableOfType(DomainException.class,
                () -> authService.login(new LoginRequest(EMAIL, "a".repeat(200))));

        assertThat(rejected.getCode()).isEqualTo(401);
    }

    @Test
    void emailIsTrimmedAndLowerCasedBeforeTheLookup() {
        when(userRepository.findByEmailAndDeletedAtIsNull(EMAIL)).thenReturn(Optional.of(user));
        when(userRepository.findRoleGrants(USER_ID)).thenReturn(List.of());
        when(jwtIssuer.issueAccessToken(USER_ID, Set.of(), Set.of())).thenReturn("signed-token");

        authService.login(new LoginRequest("  Seller@Nexus.Local ", PASSWORD));

        verify(userRepository).findByEmailAndDeletedAtIsNull(EMAIL);
    }

    @Test
    void theRequestNeverPrintsThePassword() {
        assertThat(new LoginRequest(EMAIL, PASSWORD).toString()).doesNotContain(PASSWORD);
    }

    @Test
    void fifthWrongPasswordLocksAndNextLoginReturns423() {
        when(userRepository.findByEmailAndDeletedAtIsNull(EMAIL))
                .thenReturn(Optional.of(user));

        for (int attempt = 1; attempt <= 5; attempt++) {
            DomainException failure = rejectedLogin("wrong-password");

            assertThat(failure.getCode()).isEqualTo(401);
            assertThat(user.getFailedLoginCount()).isEqualTo((short) attempt);
            assertThat(user.getStatus()).isEqualTo(
                    attempt < 5 ? UserStatus.ACTIVE : UserStatus.LOCKED
            );
        }

        assertThat(user.getLockedUntil()).isEqualTo(NOW.plusMinutes(15));
        assertThat(rejectedLogin(PASSWORD).getCode()).isEqualTo(423);
        assertThat(user.getFailedLoginCount()).isEqualTo((short) 5);

        verify(userRepository, never()).findRoleGrants(any());
        verify(jwtIssuer, never()).issueAccessToken(any(), any(), any());
    }

    @Test
    void successfulLoginBeforeTheLimitResetsFailedAttempts() {
        user.setFailedLoginCount((short) 4);
        stubSuccessfulLogin();

        LoginResponse response =
                authService.login(new LoginRequest(EMAIL, PASSWORD));

        assertThat(response.accessToken()).isEqualTo("signed-token");
        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getLastLoginAt()).isEqualTo(NOW);
    }

    @Test
    void lockedAccountRejectsCorrectAndWrongPasswordsWithoutIncreasingCount() {
        user.setStatus(UserStatus.LOCKED);
        user.setFailedLoginCount((short) 5);
        user.setLockedUntil(NOW.plusMinutes(15));

        when(userRepository.findByEmailAndDeletedAtIsNull(EMAIL))
                .thenReturn(Optional.of(user));

        assertThat(rejectedLogin(PASSWORD).getCode()).isEqualTo(423);
        assertThat(rejectedLogin("wrong-password").getCode()).isEqualTo(423);
        assertThat(user.getFailedLoginCount()).isEqualTo((short) 5);
        assertThat(user.getLockedUntil()).isEqualTo(NOW.plusMinutes(15));

        verify(userRepository, never()).findRoleGrants(any());
        verify(jwtIssuer, never()).issueAccessToken(any(), any(), any());
    }

    @Test
    void accountIsStillLockedImmediatelyBeforeExpiry() {
        user.setStatus(UserStatus.LOCKED);
        user.setFailedLoginCount((short) 5);
        user.setLockedUntil(NOW.plusNanos(1));

        when(userRepository.findByEmailAndDeletedAtIsNull(EMAIL))
                .thenReturn(Optional.of(user));

        assertThat(rejectedLogin(PASSWORD).getCode()).isEqualTo(423);
        assertThat(user.getStatus()).isEqualTo(UserStatus.LOCKED);
        assertThat(user.getFailedLoginCount()).isEqualTo((short) 5);
    }

    @Test
    void accountUnlocksExactlyAtExpiryAndCorrectPasswordSucceeds() {
        user.setStatus(UserStatus.LOCKED);
        user.setFailedLoginCount((short) 5);
        user.setLockedUntil(NOW);
        stubSuccessfulLogin();

        LoginResponse response =
                authService.login(new LoginRequest(EMAIL, PASSWORD));

        assertThat(response.accessToken()).isEqualTo("signed-token");
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getLastLoginAt()).isEqualTo(NOW);
    }

    @Test
    void wrongPasswordAfterExpiryStartsANewFailureSequence() {
        user.setStatus(UserStatus.LOCKED);
        user.setFailedLoginCount((short) 5);
        user.setLockedUntil(NOW.minusSeconds(1));

        when(userRepository.findByEmailAndDeletedAtIsNull(EMAIL))
                .thenReturn(Optional.of(user));

        assertThat(rejectedLogin("wrong-password").getCode()).isEqualTo(401);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getFailedLoginCount()).isEqualTo((short) 1);
        assertThat(user.getLockedUntil()).isNull();

        verify(jwtIssuer, never()).issueAccessToken(any(), any(), any());
    }

    @Test
    void thresholdAndLockDurationFollowConfiguration() {
        iamProperties.setMaxLoginAttempts(3);
        iamProperties.setLockDurationMinutes(7);

        when(userRepository.findByEmailAndDeletedAtIsNull(EMAIL))
                .thenReturn(Optional.of(user));

        assertThat(rejectedLogin("wrong-password").getCode()).isEqualTo(401);
        assertThat(rejectedLogin("wrong-password").getCode()).isEqualTo(401);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);

        assertThat(rejectedLogin("wrong-password").getCode()).isEqualTo(401);
        assertThat(user.getFailedLoginCount()).isEqualTo((short) 3);
        assertThat(user.getStatus()).isEqualTo(UserStatus.LOCKED);
        assertThat(user.getLockedUntil()).isEqualTo(NOW.plusMinutes(7));
        assertThat(rejectedLogin(PASSWORD).getCode()).isEqualTo(423);
    }

    @Test
    void lockWithoutExpiryNeverAutomaticallyUnlocks() {
        user.setStatus(UserStatus.LOCKED);
        user.setFailedLoginCount((short) 5);
        user.setLockedUntil(null);

        when(userRepository.findByEmailAndDeletedAtIsNull(EMAIL))
                .thenReturn(Optional.of(user));

        assertThat(rejectedLogin(PASSWORD).getCode()).isEqualTo(423);
        assertThat(user.getStatus()).isEqualTo(UserStatus.LOCKED);
        assertThat(user.getFailedLoginCount()).isEqualTo((short) 5);
    }

    private DomainException rejectedLogin(String password) {
        DomainException exception = catchThrowableOfType(
                DomainException.class,
                () -> authService.login(new LoginRequest(EMAIL, password))
        );
        assertThat(exception).isNotNull();
        return exception;
    }

    private void stubSuccessfulLogin() {
        when(userRepository.findByEmailAndDeletedAtIsNull(EMAIL))
                .thenReturn(Optional.of(user));
        when(userRepository.findRoleGrants(USER_ID)).thenReturn(List.of());
        when(jwtIssuer.issueAccessToken(USER_ID, Set.of(), Set.of()))
                .thenReturn("signed-token");
    }


    private record Grant(String role, String permission) implements UserRepository.RoleGrant {

        @Override
        public String getRole() {
            return role;
        }

        @Override
        public String getPermission() {
            return permission;
        }
    }
}
