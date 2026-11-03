package com.group5.userservice.iam;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.iam.dto.UpdateProfileRequest;
import com.group5.userservice.iam.dto.UserResponse;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.Gender;
import com.group5.userservice.iam.enums.UserStatus;
import com.group5.userservice.shared.config.IamProperties;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import com.group5.userservice.shared.PostgresTestDatabase;
import com.group5.userservice.shared.PostgresAuditingTestConfiguration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real JPA transactions and SQL constraints, without an enclosing test transaction.
 * Runs the production Flyway migrations and validates mappings against PostgreSQL 18.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ProfileTransactionTest.JpaTestConfig.class)
class ProfileTransactionTest {

    private static final String FIRST_EMAIL = "first@nexus.local";
    private static final String SECOND_EMAIL = "second@nexus.local";
    private static final LocalDate ORIGINAL_BIRTH_DATE = LocalDate.of(2000, 1, 2);

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;

    private UUID firstId;
    private UUID secondId;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            userRepository.deleteAll();
            userRepository.flush();
            firstId = seedUser(FIRST_EMAIL, "0900000001", "First User", "First City");
            secondId = seedUser(SECOND_EMAIL, "0900000002", "Second User", "Second City");
        });
    }

    @Test
    void getReadsTheRequestedOwnUserAndDoesNotChangeDatabaseData() {
        StoredProfile before = readCommittedProfile(firstId);

        UserResponse response = userService.getOwnProfile(firstId);

        assertThat(response.id()).isEqualTo(firstId);
        assertThat(response.email()).isEqualTo(FIRST_EMAIL);
        assertThat(response.fullName()).isEqualTo("First User");
        assertThat(response.phone()).isEqualTo("0900000001");
        assertThat(response.roles()).containsExactly("BUYER");
        assertThat(response.id()).isNotEqualTo(secondId);
        assertThat(readCommittedProfile(firstId)).isEqualTo(before);
    }

    @Test
    void threeFieldUpdatePersistsNormalizedValuesAndRetainsProtectedAndOmittedFields() {
        StoredProfile before = readCommittedProfile(firstId);
        StoredProfile otherBefore = readCommittedProfile(secondId);

        UserResponse response = userService.updateOwnProfile(firstId,
                new UpdateProfileRequest("  Updated Name  ", null, null,
                        "  HCM  ", "  X@NEXUS.LOCAL  "));

        assertThat(response.id()).isEqualTo(firstId);
        assertThat(response.fullName()).isEqualTo("Updated Name");
        assertThat(response.email()).isEqualTo("x@nexus.local");
        assertThat(response.address()).isEqualTo("HCM");
        assertThat(response.roles()).containsExactly("BUYER");

        StoredProfile after = readCommittedProfile(firstId);
        assertThat(after.fullName()).isEqualTo("Updated Name");
        assertThat(after.email()).isEqualTo("x@nexus.local");
        assertThat(after.address()).isEqualTo("HCM");
        assertThat(after.passwordHash()).isEqualTo(before.passwordHash());
        assertThat(after.phone()).isEqualTo(before.phone());
        assertThat(after.identityNumber()).isEqualTo(before.identityNumber());
        assertThat(after.dateOfBirth()).isEqualTo(before.dateOfBirth());
        assertThat(after.gender()).isEqualTo(before.gender());
        assertThat(after.status()).isEqualTo(before.status());
        assertThat(after.failedLoginCount()).isEqualTo(before.failedLoginCount());
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(readCommittedProfile(secondId)).isEqualTo(otherBefore);
    }

    @Test
    void dateOfBirthAndGenderAreEditableWithoutChangingOmittedFields() {
        StoredProfile before = readCommittedProfile(firstId);
        LocalDate changedBirthDate = LocalDate.of(1998, 3, 4);

        UserResponse response = userService.updateOwnProfile(firstId,
                new UpdateProfileRequest(null, changedBirthDate, Gender.FEMALE, null, null));

        assertThat(response.dateOfBirth()).isEqualTo(changedBirthDate);
        assertThat(response.gender()).isEqualTo(Gender.FEMALE);
        StoredProfile after = readCommittedProfile(firstId);
        assertThat(after.dateOfBirth()).isEqualTo(changedBirthDate);
        assertThat(after.gender()).isEqualTo(Gender.FEMALE);
        assertThat(after.fullName()).isEqualTo(before.fullName());
        assertThat(after.email()).isEqualTo(before.email());
        assertThat(after.address()).isEqualTo(before.address());
        assertThat(after.passwordHash()).isEqualTo(before.passwordHash());
    }

    @Test
    void keepingMyOwnEmailDoesNotProduceADuplicateConflict() {
        UserResponse response = userService.updateOwnProfile(firstId,
                new UpdateProfileRequest("New Name", null, null, null,
                        "  FIRST@NEXUS.LOCAL  "));

        assertThat(response.email()).isEqualTo(FIRST_EMAIL);
        assertThat(readCommittedProfile(firstId).fullName()).isEqualTo("New Name");
    }

    @Test
    void duplicatePrecheckReturns409AndRetainsTheWholeOriginalProfile() {
        StoredProfile before = readCommittedProfile(firstId);

        assertConflict(() -> userService.updateOwnProfile(firstId,
                new UpdateProfileRequest("Rejected Name", LocalDate.of(1990, 1, 1),
                        Gender.OTHER, "Rejected City", "  SECOND@NEXUS.LOCAL  ")));

        assertThat(readCommittedProfile(firstId)).isEqualTo(before);
    }

    @Test
    void aSoftDeletedUsersEmailRemainsReservedByTheDatabaseUniqueConstraint() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User user = userRepository.findById(secondId).orElseThrow();
            user.setDeletedAt(OffsetDateTime.now().minusDays(1));
            userRepository.saveAndFlush(user);
        });
        StoredProfile before = readCommittedProfile(firstId);

        assertConflict(() -> userService.updateOwnProfile(firstId,
                new UpdateProfileRequest("Rejected Name", null, null,
                        "Rejected City", SECOND_EMAIL)));

        assertThat(readCommittedProfile(firstId)).isEqualTo(before);
    }

    @Test
    void aSoftDeletedCallerCannotReadOrUpdateTheirProfile() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User user = userRepository.findById(firstId).orElseThrow();
            user.setDeletedAt(OffsetDateTime.now().minusDays(1));
            userRepository.saveAndFlush(user);
        });
        StoredProfile before = readCommittedProfile(firstId);

        DomainException readError = catchThrowableOfType(DomainException.class,
                () -> userService.getOwnProfile(firstId));
        DomainException updateError = catchThrowableOfType(DomainException.class,
                () -> userService.updateOwnProfile(firstId,
                        new UpdateProfileRequest("Rejected Name", null, null, null, null)));

        assertThat(readError).isNotNull();
        assertThat(readError.getCode()).isEqualTo(404);
        assertThat(updateError).isNotNull();
        assertThat(updateError.getCode()).isEqualTo(404);
        assertThat(readCommittedProfile(firstId)).isEqualTo(before);
    }

    @Test
    void anActualSqlUniqueViolationReturns409AfterRollingBackAllChangedFields() {
        // Only bypass the precheck. Reads, writes, constraints and rollback remain real.
        UserRepository bypass = mock(UserRepository.class, delegatesTo(userRepository));
        doReturn(false).when(bypass).existsByEmailAndIdNot(anyString(), any(UUID.class));
        UserService service = serviceUsing(bypass);
        StoredProfile before = readCommittedProfile(firstId);

        assertConflict(() -> service.updateOwnProfile(firstId,
                new UpdateProfileRequest("Rejected Name", LocalDate.of(1990, 1, 1),
                        Gender.OTHER, "Rejected City", SECOND_EMAIL)));

        assertThat(readCommittedProfile(firstId)).isEqualTo(before);
        assertThat(userService.getOwnProfile(firstId).email()).isEqualTo(FIRST_EMAIL);
    }

    @Test
    void aNonUniqueSqlViolationIsNotMisreportedAsAnEmailConflictAndStillRollsBack() {
        jdbc.execute("ALTER TABLE users ADD CONSTRAINT profile_address_check "
                + "CHECK (address <> 'reject-update')");
        StoredProfile before = readCommittedProfile(firstId);
        try {
            assertThatThrownBy(() -> userService.updateOwnProfile(firstId,
                    new UpdateProfileRequest("Rejected Name", null, null,
                            "reject-update", "new@nexus.local")))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(readCommittedProfile(firstId)).isEqualTo(before);
        } finally {
            jdbc.execute("ALTER TABLE users DROP CONSTRAINT profile_address_check");
        }
    }

    @Test
    void twoUsersCompetingForOneEmailProduceOneSuccessAndOne409WithNoPartialLoserUpdate()
            throws Exception {
        String targetEmail = "shared@nexus.local";
        CyclicBarrier bothPrechecksCompleted = new CyclicBarrier(2);
        UserRepository synchronizedChecks = mock(UserRepository.class, delegatesTo(userRepository));
        doAnswer(invocation -> {
            boolean exists = userRepository.existsByEmailAndIdNot(
                    invocation.getArgument(0), invocation.getArgument(1));
            assertThat(exists).isFalse();
            bothPrechecksCompleted.await(10, TimeUnit.SECONDS);
            return exists;
        }).when(synchronizedChecks).existsByEmailAndIdNot(eq(targetEmail), any(UUID.class));
        UserService service = serviceUsing(synchronizedChecks);
        StoredProfile firstBefore = readCommittedProfile(firstId);
        StoredProfile secondBefore = readCommittedProfile(secondId);

        var executor = Executors.newFixedThreadPool(2);
        List<UpdateResult> results;
        try {
            var first = executor.submit(() -> attemptUpdate(service, firstId,
                    "First Changed", "First Changed City", targetEmail));
            var second = executor.submit(() -> attemptUpdate(service, secondId,
                    "Second Changed", "Second Changed City", targetEmail));
            results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(results).extracting(UpdateResult::status).containsExactlyInAnyOrder(200, 409);
        UpdateResult winner = results.stream().filter(result -> result.status() == 200)
                .findFirst().orElseThrow();
        UpdateResult loser = results.stream().filter(result -> result.status() == 409)
                .findFirst().orElseThrow();
        StoredProfile winnerStored = readCommittedProfile(winner.userId());
        assertThat(winnerStored.email()).isEqualTo(targetEmail);
        assertThat(winnerStored.fullName()).isEqualTo(winner.newName());
        assertThat(winnerStored.address()).isEqualTo(winner.newAddress());
        assertThat(readCommittedProfile(loser.userId()))
                .isEqualTo(loser.userId().equals(firstId) ? firstBefore : secondBefore);
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?",
                Long.class, targetEmail)).isEqualTo(1L);
    }

    private UUID seedUser(String email, String phone, String fullName, String address) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode("original-password"));
        user.setFullName(fullName);
        user.setIdentityNumber("ID-" + phone);
        user.setDateOfBirth(ORIGINAL_BIRTH_DATE);
        user.setGender(Gender.MALE);
        user.setAddress(address);
        user.setFailedLoginCount((short) 3);
        return userRepository.saveAndFlush(user).getId();
    }

    private static KeycloakAdminClient buyerKeycloak() {
        KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
        when(keycloak.realmRoles(any())).thenReturn(List.of("BUYER"));
        return keycloak;
    }

    private UserService serviceUsing(UserRepository repository) {
        return new UserService(repository, passwordEncoder, new IamProperties(), buyerKeycloak(),
                transactionManager);
    }

    private void assertConflict(Runnable action) {
        DomainException conflict = catchThrowableOfType(DomainException.class, action::run);
        assertThat(conflict).isNotNull();
        assertThat(conflict.getCode()).isEqualTo(409);
    }

    private UpdateResult attemptUpdate(UserService service, UUID userId,
                                       String name, String address, String email) {
        try {
            service.updateOwnProfile(userId,
                    new UpdateProfileRequest(name, null, null, address, email));
            return new UpdateResult(userId, 200, name, address);
        } catch (DomainException conflict) {
            return new UpdateResult(userId, conflict.getCode(), name, address);
        }
    }

    // A new JDBC connection observes committed rows after each service call returns or throws.
    private StoredProfile readCommittedProfile(UUID id) {
        return jdbc.queryForObject("""
                select email, phone, password_hash, full_name, identity_number,
                       date_of_birth, gender, address, status, failed_login_count, version
                from users where id = ?
                """, (row, index) -> new StoredProfile(
                row.getString("email"), row.getString("phone"), row.getString("password_hash"),
                row.getString("full_name"), row.getString("identity_number"),
                row.getObject("date_of_birth", LocalDate.class),
                Gender.valueOf(row.getString("gender")), row.getString("address"),
                UserStatus.valueOf(row.getString("status")), row.getShort("failed_login_count"),
                row.getLong("version")), id);
    }

    private record StoredProfile(String email, String phone, String passwordHash,
                                 String fullName, String identityNumber, LocalDate dateOfBirth,
                                 Gender gender, String address, UserStatus status,
                                 short failedLoginCount, long version) {
    }

    private record UpdateResult(UUID userId, int status, String newName, String newAddress) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(PostgresAuditingTestConfiguration.class)
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    static class JpaTestConfig {

        @Bean
        DataSource dataSource() {
            return PostgresTestDatabase.migratedDataSource("own_profile");
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(User.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }

        @Bean
        UserService userService(UserRepository repository, PlatformTransactionManager manager,
                                PasswordEncoder encoder) {
            return new UserService(repository, encoder, new IamProperties(), buyerKeycloak(), manager);
        }
    }
}
