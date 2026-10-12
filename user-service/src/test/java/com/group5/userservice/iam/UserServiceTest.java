package com.group5.userservice.iam;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.iam.dto.RegisterRequest;
import com.group5.userservice.iam.dto.UserResponse;
import com.group5.userservice.iam.entity.Role;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final MockEnvironment environment = new MockEnvironment()
            .withProperty("nexus.iam.default-user-role", "BUYER")
            .withProperty("nexus.iam.allow-self-registration", "true");

    private UserService userService;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        userService = new UserService(userRepository, passwordEncoder, environment, transactionManager);
    }

    @Test
    void registerReturnsTheConfiguredDefaultRole() {
        givenRegistrationCanSave();

        UserResponse response = userService.register(validRequest());

        assertThat(response.roles()).containsExactly("BUYER");
    }

    @Test
    void registerDefaultsToBuyerAndAllowSelfRegistrationWhenPropertiesAreAbsent() {
        UserService serviceWithAbsentProperties =
                new UserService(userRepository, passwordEncoder, new MockEnvironment(), transactionManager);
        givenRegistrationCanSave();

        UserResponse response = serviceWithAbsentProperties.register(validRequest());

        assertThat(response.roles()).containsExactly("BUYER");
    }

    @Test
    void registerStoresExactlyOneConfiguredDefaultRole() {
        givenRegistrationCanSave();
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);

        userService.register(validRequest());

        verify(userRepository).saveAndFlush(savedUser.capture());
        assertThat(savedUser.getValue().getRoles())
                .extracting(Role::getCode)
                .containsExactly("BUYER");
    }

    @Test
    void registerHashesThePasswordBeforeSavingTheUser() {
        givenRegistrationCanSave();
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);

        userService.register(validRequest());

        verify(userRepository).saveAndFlush(savedUser.capture());
        assertThat(savedUser.getValue().getPasswordHash()).isNotEqualTo("correct-password");
        assertThat(passwordEncoder.matches("correct-password", savedUser.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void registerNormalizesEmailBeforeDuplicateChecksAndStorage() {
        givenRegistrationCanSave();
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);

        userService.register(new RegisterRequest(
                "  Buyer@Nexus.Local  ",
                "0901234567",
                "correct-password",
                "Buyer One",
                "ID123456",
                "MALE",
                "1 Main Street",
                "2000-01-01"));

        verify(userRepository).existsByEmail("buyer@nexus.local");
        verify(userRepository).saveAndFlush(savedUser.capture());
        assertThat(savedUser.getValue().getEmail()).isEqualTo("buyer@nexus.local");
    }

    @Test
    void registerNormalizesPhoneBeforeDuplicateChecksAndStorage() {
        givenRegistrationCanSave();
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);

        userService.register(new RegisterRequest(
                "buyer@nexus.local",
                " 090-123 45(67) ",
                "correct-password",
                "Buyer One",
                "ID123456",
                "MALE",
                "1 Main Street",
                "2000-01-01"));

        verify(userRepository).existsByPhone("0901234567");
        verify(userRepository).saveAndFlush(savedUser.capture());
        assertThat(savedUser.getValue().getPhone()).isEqualTo("0901234567");
    }

    @Test
    void selfRegistrationDisabledReturns403BeforeOpeningATransaction() {
        environment.setProperty("nexus.iam.allow-self-registration", "false");

        DomainException rejected = catchThrowableOfType(DomainException.class,
                () -> userService.register(validRequest()));

        assertThat(rejected.getCode()).isEqualTo(403);
        verifyNoInteractions(userRepository);
        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    void selfRegistrationFlagIsReadAgainOnTheNextCall() {
        givenRegistrationCanSave();
        userService.register(validRequest());
        environment.setProperty("nexus.iam.allow-self-registration", "false");

        DomainException rejected = catchThrowableOfType(DomainException.class,
                () -> userService.register(validRequestWithEmail("second@nexus.local")));

        assertThat(rejected.getCode()).isEqualTo(403);
    }

    @Test
    void duplicateEmailReturns409WithoutSaving() {
        when(userRepository.existsByEmail("buyer@nexus.local")).thenReturn(true);

        DomainException rejected = catchThrowableOfType(DomainException.class,
                () -> userService.register(validRequest()));

        assertThat(rejected.getCode()).isEqualTo(409);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void duplicatePhoneReturns409WithoutSaving() {
        when(userRepository.existsByEmail("buyer@nexus.local")).thenReturn(false);
        when(userRepository.existsByPhone("0901234567")).thenReturn(true);

        DomainException rejected = catchThrowableOfType(DomainException.class,
                () -> userService.register(validRequest()));

        assertThat(rejected.getCode()).isEqualTo(409);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void duplicateIdentityNumberReturns409WithoutSaving() {
        when(userRepository.existsByEmail("buyer@nexus.local")).thenReturn(false);
        when(userRepository.existsByPhone("0901234567")).thenReturn(false);
        when(userRepository.lockRegistrationIdentity("ID123456")).thenReturn(1);
        when(userRepository.existsByIdentityNumber("ID123456")).thenReturn(true);

        DomainException rejected = catchThrowableOfType(DomainException.class,
                () -> userService.register(validRequest()));

        assertThat(rejected.getCode()).isEqualTo(409);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void concurrentDatabaseUniqueViolationReturns409() {
        givenNoDuplicates();
        when(userRepository.findRegistrationRole("BUYER")).thenReturn(Optional.of(buyerRole()));
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate email",
                        new org.hibernate.exception.ConstraintViolationException(
                                "duplicate", new SQLException("duplicate", "23505"), "users_email_key")));

        DomainException rejected = catchThrowableOfType(DomainException.class,
                () -> userService.register(validRequest()));

        assertThat(rejected.getCode()).isEqualTo(409);
        verify(transactionManager).rollback(transactionStatus);
    }

    @Test
    void concurrentPhoneUniqueViolationReturns409AfterRollback() {
        givenNoDuplicates();
        when(userRepository.findRegistrationRole("BUYER")).thenReturn(Optional.of(buyerRole()));
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate phone",
                        new org.hibernate.exception.ConstraintViolationException(
                                "duplicate", new SQLException("duplicate", "23505"), "users_phone_key")));

        DomainException rejected = catchThrowableOfType(DomainException.class,
                () -> userService.register(validRequest()));

        assertThat(rejected.getCode()).isEqualTo(409);
        verify(transactionManager).rollback(transactionStatus);
    }

    @Test
    void nonDuplicateDatabaseConstraintViolationKeepsTheOriginalException() {
        givenNoDuplicates();
        when(userRepository.findRegistrationRole("BUYER")).thenReturn(Optional.of(buyerRole()));
        DataIntegrityViolationException original = new DataIntegrityViolationException("foreign key",
                new org.hibernate.exception.ConstraintViolationException(
                        "foreign key", new SQLException("foreign key", "23503"), "user_roles_role_id_fkey"));
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(original);

        DataIntegrityViolationException rejected = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> userService.register(validRequest()));

        assertThat(rejected).isSameAs(original);
        verify(transactionManager).rollback(transactionStatus);
    }

    @Test
    void emailUniqueViolationAtCommitReturns409InsteadOfSuccessfulRegistration() {
        givenRegistrationCanSave();
        doThrow(new DataIntegrityViolationException("duplicate email at commit",
                new org.hibernate.exception.ConstraintViolationException(
                        "duplicate", new SQLException("duplicate", "23505"), "users_email_key")))
                .when(transactionManager)
                .commit(transactionStatus);

        DomainException rejected = catchThrowableOfType(DomainException.class,
                () -> userService.register(validRequest()));

        assertThat(rejected.getCode()).isEqualTo(409);
        verify(transactionManager).commit(transactionStatus);
    }

    private void givenRegistrationCanSave() {
        givenNoDuplicates();
        when(userRepository.findRegistrationRole("BUYER")).thenReturn(Optional.of(buyerRole()));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(UUID.fromString("7c47f673-6a1f-4b7b-bfb6-90e5b674eb81"));
            user.setStatus(UserStatus.ACTIVE);
            user.setCreatedAt(OffsetDateTime.parse("2026-01-02T03:04:05Z"));
            return user;
        });
    }

    private void givenNoDuplicates() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByPhone(anyString())).thenReturn(false);
        when(userRepository.lockRegistrationIdentity(anyString())).thenReturn(1);
        when(userRepository.existsByIdentityNumber(anyString())).thenReturn(false);
    }

    private Role buyerRole() {
        Role role = new Role();
        role.setId(UUID.fromString("f78f7e48-7672-48f2-b5d3-d5b615d512d7"));
        role.setCode("BUYER");
        role.setName("Buyer");
        return role;
    }

    private RegisterRequest validRequest() {
        return validRequestWithEmail("buyer@nexus.local");
    }

    private RegisterRequest validRequestWithEmail(String email) {
        return new RegisterRequest(
                email,
                "0901234567",
                "correct-password",
                "Buyer One",
                "ID123456",
                "MALE",
                "1 Main Street",
                "2000-01-01");
    }
}
