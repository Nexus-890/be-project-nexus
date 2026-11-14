package com.group5.userservice.iam;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.iam.dto.UpdateProfileRequest;
import com.group5.userservice.iam.dto.UserResponse;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.Gender;
import com.group5.userservice.iam.enums.UserStatus;
import com.group5.userservice.shared.config.IamProperties;
import com.group5.userservice.shared.exception.UserErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    private static final UUID USER_ID = UUID.fromString("35754f22-99b9-4a70-b8ea-612b876ba5ba");
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-01-02T03:04:05Z");

    @Mock
    private UserRepository userRepository;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionStatus transactionStatus;
    @Mock
    private KeycloakAdminClient keycloak;
    @Mock
    private EmailVerificationService emailVerification;

    private final IamProperties iamProperties = new IamProperties();
    private UserService userService;
    private User user;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        userService = new UserService(userRepository, iamProperties, keycloak, transactionManager, emailVerification);
        user = existingUser();
    }

    @Test
    void getReturnsTheRequestedCallerAndExistingRegistrationResponseWithoutWriting() {
        when(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.of(user));
        givenKeycloakRoles();

        UserResponse response = userService.getOwnProfile(USER_ID);

        assertThat(response.id()).isEqualTo(USER_ID);
        assertThat(response.fullName()).isEqualTo("Original Name");
        assertThat(response.email()).isEqualTo("buyer@nexus.local");
        assertThat(response.phone()).isEqualTo("0900000001");
        assertThat(response.roles()).containsExactly("BUYER");
        assertThat(response.createdAt()).isEqualTo(CREATED_AT);
        verify(userRepository).findByIdAndDeletedAtIsNull(USER_ID);
        verify(userRepository, never()).saveAndFlush(any());
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    void aMissingOrSoftDeletedCallerReturns404OnGet() {
        when(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.empty());

        DomainException error = catchThrowableOfType(DomainException.class,
                () -> userService.getOwnProfile(USER_ID));

        assertThat(error.getCode()).isEqualTo(404);
        verify(transactionManager).rollback(transactionStatus);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void threeFieldUpdateNormalizesEmailAndRetainsPhoneIdentityAndStatus() {
        givenCaller();
        when(userRepository.saveAndFlush(user)).thenReturn(user);
        givenKeycloakAccount(false);
        givenKeycloakRoles();

        UserResponse response = userService.updateOwnProfile(USER_ID,
                new UpdateProfileRequest("  Changed Name  ", null, null, "  HCM  ", "  X@NEXUS.LOCAL  "));

        assertThat(response.fullName()).isEqualTo("Changed Name");
        assertThat(response.email()).isEqualTo("x@nexus.local");
        assertThat(response.address()).isEqualTo("HCM");
        assertThat(response.roles()).containsExactly("BUYER");
        assertThat(user.getPhone()).isEqualTo("0900000001");
        assertThat(user.getIdentityNumber()).isEqualTo("ID-0900000001");
        assertThat(user.getDateOfBirth()).isEqualTo(LocalDate.of(2000, 1, 2));
        assertThat(user.getGender()).isEqualTo(Gender.MALE);
        assertThat(user.getStatus()).isEqualTo(UserStatus.LOCKED);
        verify(userRepository).existsByEmailAndIdNot("x@nexus.local", USER_ID);
        verify(keycloak).updateEmail(USER_ID, "x@nexus.local", false);
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    void aRequestWithNoEditableValuesRetainsTheWholeProfileAndSkipsEmailLookup() {
        givenCaller();
        when(userRepository.saveAndFlush(user)).thenReturn(user);
        UserResponse before = UserResponse.from(user, List.of());

        UserResponse response = userService.updateOwnProfile(USER_ID,
                new UpdateProfileRequest(null, null, null, null, null));

        assertThat(response).isEqualTo(before);
        verify(userRepository, never()).existsByEmailAndIdNot(anyString(), any());
    }

    @Test
    void duplicateEmailReturns409BeforeMutatingOtherFields() {
        givenCaller();
        when(userRepository.existsByEmailAndIdNot("other@nexus.local", USER_ID)).thenReturn(true);
        UserResponse before = UserResponse.from(user, List.of());

        DomainException error = catchThrowableOfType(DomainException.class,
                () -> userService.updateOwnProfile(USER_ID,
                        new UpdateProfileRequest("Rejected Name", LocalDate.of(1990, 1, 2),
                                Gender.OTHER, "Rejected Address", "  OTHER@NEXUS.LOCAL  ")));

        assertThat(error.getCode()).isEqualTo(409);
        assertThat(UserResponse.from(user, List.of())).isEqualTo(before);
        verify(userRepository, never()).saveAndFlush(any());
        verify(transactionManager).rollback(transactionStatus);
    }

    @Test
    void aMissingOrSoftDeletedCallerReturns404OnUpdateWithoutSaving() {
        when(userRepository.findForProfileUpdate(USER_ID)).thenReturn(Optional.empty());

        DomainException error = catchThrowableOfType(DomainException.class,
                () -> userService.updateOwnProfile(USER_ID,
                        new UpdateProfileRequest("Changed Name", null, null, null, null)));

        assertThat(error.getCode()).isEqualTo(404);
        verify(userRepository, never()).saveAndFlush(any());
        verify(transactionManager).rollback(transactionStatus);
    }

    @Test
    void disablingSelfRegistrationDoesNotDisableAnExistingUsersProfile() {
        iamProperties.setAllowSelfRegistration(false);
        iamProperties.setDefaultUserRole("SELLER");
        givenCaller();
        when(userRepository.saveAndFlush(user)).thenReturn(user);
        givenKeycloakRoles();

        UserResponse response = userService.updateOwnProfile(USER_ID,
                new UpdateProfileRequest("Changed Name", null, null, null, null));

        assertThat(response.fullName()).isEqualTo("Changed Name");
        assertThat(response.roles()).containsExactly("BUYER");
        verify(keycloak, never()).realmRole(anyString());
        verify(keycloak, never()).updateEmail(any(), anyString(), anyBoolean());
    }

    @Test
    void databaseEmailUniqueViolationIsTranslatedAfterTransactionRollback() {
        givenCaller();
        when(userRepository.saveAndFlush(user)).thenThrow(uniqueViolation("users_email_key"));

        DomainException error = catchThrowableOfType(DomainException.class,
                () -> userService.updateOwnProfile(USER_ID,
                        new UpdateProfileRequest(null, null, null, null, "changed@nexus.local")));

        assertThat(error.getCode()).isEqualTo(409);
        assertThat(error.getMessage()).contains("email");
        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void aSqlUniqueViolationWithAnUnknownDatabaseConstraintStillReturns409() {
        givenCaller();
        when(userRepository.saveAndFlush(user)).thenThrow(uniqueViolation("generated_unique_index"));

        DomainException error = catchThrowableOfType(DomainException.class,
                () -> userService.updateOwnProfile(USER_ID,
                        new UpdateProfileRequest(null, null, null, null, "changed@nexus.local")));

        assertThat(error.getCode()).isEqualTo(409);
        verify(transactionManager).rollback(transactionStatus);
    }

    @Test
    void aJdbcUniqueViolationWithoutAHibernateWrapperStillReturns409() {
        givenCaller();
        when(userRepository.saveAndFlush(user)).thenThrow(new DataIntegrityViolationException(
                "duplicate email", new SQLException("duplicate", "23505")));

        DomainException error = catchThrowableOfType(DomainException.class,
                () -> userService.updateOwnProfile(USER_ID,
                        new UpdateProfileRequest(null, null, null, null, "changed@nexus.local")));

        assertThat(error.getCode()).isEqualTo(409);
        verify(transactionManager).rollback(transactionStatus);
    }

    @Test
    void aNonUniqueDatabaseViolationRetainsItsOriginalException() {
        givenCaller();
        DataIntegrityViolationException original = new DataIntegrityViolationException("foreign key",
                new ConstraintViolationException("foreign key", new SQLException("foreign key", "23503"),
                        "foreign_key_constraint"));
        when(userRepository.saveAndFlush(user)).thenThrow(original);

        DataIntegrityViolationException error = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> userService.updateOwnProfile(USER_ID,
                        new UpdateProfileRequest("Changed Name", null, null, null, null)));

        assertThat(error).isSameAs(original);
        verify(transactionManager).rollback(transactionStatus);
    }

    @Test
    void anEmailUniqueViolationAtCommitReturns409RatherThanASuccessfulUpdate() {
        givenCaller();
        when(userRepository.saveAndFlush(user)).thenReturn(user);
        givenKeycloakAccount(true);
        doThrow(uniqueViolation("users_email_key")).when(transactionManager).commit(transactionStatus);

        DomainException error = catchThrowableOfType(DomainException.class,
                () -> userService.updateOwnProfile(USER_ID,
                        new UpdateProfileRequest(null, null, null, null, "changed@nexus.local")));

        assertThat(error.getCode()).isEqualTo(409);
        verify(transactionManager).commit(transactionStatus);
        verify(keycloak).updateEmail(USER_ID, "changed@nexus.local", false);
        verify(keycloak).updateEmail(USER_ID, "buyer@nexus.local", true);
    }

    @Test
    void anUnchangedEmailIsNotSentToKeycloak() {
        givenCaller();
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        userService.updateOwnProfile(USER_ID, new UpdateProfileRequest(null, null, null, null, "Buyer@Nexus.Local"));

        verify(keycloak, never()).updateEmail(any(), anyString(), anyBoolean());
    }

    @Test
    void anEmailTakenInKeycloakReturns409AndRollsBackTheProfile() {
        givenCaller();
        when(userRepository.saveAndFlush(user)).thenReturn(user);
        givenKeycloakAccount(true);
        doThrow(new DomainException(UserErrorCode.USER_EMAIL_ALREADY_USED))
                .when(keycloak).updateEmail(USER_ID, "taken@nexus.local", false);

        DomainException error = catchThrowableOfType(DomainException.class,
                () -> userService.updateOwnProfile(USER_ID,
                        new UpdateProfileRequest(null, null, null, null, "taken@nexus.local")));

        assertThat(error.getErrorCode()).isEqualTo("USER_EMAIL_ALREADY_USED");
        verify(transactionManager).rollback(transactionStatus);
        verify(keycloak, times(1)).updateEmail(any(), anyString(), anyBoolean());
    }

    @Test
    void anUnknownKeycloakResultRestoresThePreviousEmailAndVerification() {
        givenCaller();
        when(userRepository.saveAndFlush(user)).thenReturn(user);
        givenKeycloakAccount(true);
        doThrow(new DomainException(UserErrorCode.USER_IDENTITY_PROVIDER_UNAVAILABLE))
                .when(keycloak).updateEmail(USER_ID, "timeout@nexus.local", false);

        DomainException error = catchThrowableOfType(DomainException.class,
                () -> userService.updateOwnProfile(USER_ID,
                        new UpdateProfileRequest(null, null, null, null, "timeout@nexus.local")));

        assertThat(error.getErrorCode()).isEqualTo("USER_IDENTITY_PROVIDER_UNAVAILABLE");
        verify(transactionManager).rollback(transactionStatus);
        verify(keycloak).updateEmail(USER_ID, "buyer@nexus.local", true);
    }

    @Test
    void aRejectedKeycloakEmailIsNotRestored() {
        givenCaller();
        when(userRepository.saveAndFlush(user)).thenReturn(user);
        givenKeycloakAccount(true);
        doThrow(new DomainException(UserErrorCode.USER_IDENTITY_REJECTED))
                .when(keycloak).updateEmail(USER_ID, "rejected@nexus.local", false);

        DomainException error = catchThrowableOfType(DomainException.class,
                () -> userService.updateOwnProfile(USER_ID,
                        new UpdateProfileRequest(null, null, null, null, "rejected@nexus.local")));

        assertThat(error.getErrorCode()).isEqualTo("USER_IDENTITY_REJECTED");
        verify(keycloak, never()).updateEmail(USER_ID, "buyer@nexus.local", true);
    }

    @Test
    void aFailedRestoreKeepsTheOriginalErrorWithTheRestoreFailureSuppressed() {
        givenCaller();
        when(userRepository.saveAndFlush(user)).thenReturn(user);
        givenKeycloakAccount(true);
        doThrow(new DomainException(UserErrorCode.USER_IDENTITY_PROVIDER_UNAVAILABLE),
                new DomainException(UserErrorCode.USER_IDENTITY_PROVIDER_UNAVAILABLE))
                .when(keycloak).updateEmail(any(), anyString(), anyBoolean());

        DomainException error = catchThrowableOfType(DomainException.class,
                () -> userService.updateOwnProfile(USER_ID,
                        new UpdateProfileRequest(null, null, null, null, "timeout@nexus.local")));

        assertThat(error.getErrorCode()).isEqualTo("USER_IDENTITY_PROVIDER_UNAVAILABLE");
        assertThat(error.getSuppressed()).hasSize(1);
        verify(keycloak).updateEmail(USER_ID, "buyer@nexus.local", true);
    }

    @Test
    void aProfileWithoutKeycloakAccountChangesOnlyTheProfileEmail() {
        givenCaller();
        when(userRepository.saveAndFlush(user)).thenReturn(user);
        when(keycloak.emailState(USER_ID)).thenReturn(Optional.empty());
        givenKeycloakRoles();

        UserResponse response = userService.updateOwnProfile(USER_ID,
                new UpdateProfileRequest(null, null, null, null, "legacy@nexus.local"));

        assertThat(response.email()).isEqualTo("legacy@nexus.local");
        verify(keycloak, never()).updateEmail(any(), anyString(), anyBoolean());
        verify(transactionManager).commit(transactionStatus);
    }

    private void givenCaller() {
        when(userRepository.findForProfileUpdate(USER_ID)).thenReturn(Optional.of(user));
    }

    private void givenKeycloakAccount(boolean emailVerified) {
        when(keycloak.emailState(USER_ID))
                .thenReturn(Optional.of(new KeycloakAdminClient.EmailState("buyer@nexus.local", emailVerified)));
    }

    private void givenKeycloakRoles() {
        when(keycloak.realmRoles(USER_ID)).thenReturn(List.of("BUYER"));
    }

    private DataIntegrityViolationException uniqueViolation(String constraint) {
        return new DataIntegrityViolationException("duplicate email",
                new ConstraintViolationException("duplicate", new SQLException("duplicate", "23505"), constraint));
    }

    private User existingUser() {
        User existing = new User();
        existing.setId(USER_ID);
        existing.setEmail("buyer@nexus.local");
        existing.setPhone("0900000001");
        existing.setFullName("Original Name");
        existing.setIdentityNumber("ID-0900000001");
        existing.setGender(Gender.MALE);
        existing.setDateOfBirth(LocalDate.of(2000, 1, 2));
        existing.setAddress("Original City");
        existing.setStatus(UserStatus.LOCKED);
        existing.setCreatedAt(CREATED_AT);
        return existing;
    }
}
