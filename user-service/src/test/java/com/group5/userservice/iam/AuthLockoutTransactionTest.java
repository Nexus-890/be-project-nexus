package com.group5.userservice.iam;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.iam.dto.LoginRequest;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.UserStatus;
import com.group5.userservice.shared.config.IamProperties;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Real Spring transaction proxy + JPA + database; no surrounding test transaction.
 * H2 verifies commit/rollback and row-lock behavior, not PostgreSQL migration compatibility.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AuthLockoutTransactionTest.JpaTestConfig.class)
class AuthLockoutTransactionTest {

    private static final String EMAIL = "seller@nexus.local";
    private static final String PASSWORD = "correct-password";
    private static final Instant INSTANT = Instant.parse("2026-10-13T08:00:00Z");
    private static final OffsetDateTime NOW = INSTANT.atOffset(ZoneOffset.UTC);

    @Autowired
    private AuthService authService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtIssuer jwtIssuer;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;

    private UUID userId;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        assertThat(AopUtils.isAopProxy(authService)).isTrue();
        reset(jwtIssuer);
        when(jwtIssuer.issueAccessToken(any(), any(), any())).thenReturn("signed-token");
        jdbc = new JdbcTemplate(dataSource);
        userId = new TransactionTemplate(transactionManager).execute(status -> {
            userRepository.deleteAll();
            // Flush deletes before reusing unique email/phone; Hibernate otherwise inserts first.
            userRepository.flush();
            User user = new User();
            user.setEmail(EMAIL);
            user.setPhone("0900000001");
            user.setFullName("Test Seller");
            user.setPasswordHash(passwordEncoder.encode(PASSWORD));
            return userRepository.saveAndFlush(user).getId();
        });
    }

    @Test
    void wrongPasswordCommitsTheCounterDespiteThe401Exception() {
        assertRejected("wrong-password", 401);

        PersistedUser stored = readCommittedUser();
        assertThat(stored.failedLoginCount()).isEqualTo((short) 1);
        assertThat(stored.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(stored.lockedUntil()).isNull();
    }

    @Test
    void theFifthFailureCommitsTheLockAndTheNextCorrectPasswordReturns423() {
        reachLockThreshold();

        PersistedUser locked = readCommittedUser();
        assertThat(locked.failedLoginCount()).isEqualTo((short) 5);
        assertThat(locked.status()).isEqualTo(UserStatus.LOCKED);
        assertThat(locked.lockedUntil()).isEqualTo(NOW.plusMinutes(15));

        assertRejected(PASSWORD, 423);
        assertRejected("wrong-password", 423);
        assertThat(readCommittedUser()).isEqualTo(locked);
    }

    @Test
    void successBeforeTheLimitCommitsTheCounterReset() {
        assertRejected("wrong-password", 401);
        assertRejected("wrong-password", 401);
        assertThat(readCommittedUser().failedLoginCount()).isEqualTo((short) 2);

        assertThat(authService.login(new LoginRequest(EMAIL, PASSWORD)).accessToken())
                .isEqualTo("signed-token");

        PersistedUser stored = readCommittedUser();
        assertThat(stored.failedLoginCount()).isZero();
        assertThat(stored.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(stored.lockedUntil()).isNull();
        assertThat(stored.lastLoginAt()).isEqualTo(NOW);
    }

    @Test
    void anExpiredLockUnlocksAndStartsANewFailureSequence() {
        reachLockThreshold();
        expireLock();

        assertRejected("wrong-password", 401);

        PersistedUser stored = readCommittedUser();
        assertThat(stored.failedLoginCount()).isEqualTo((short) 1);
        assertThat(stored.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(stored.lockedUntil()).isNull();

        authService.login(new LoginRequest(EMAIL, PASSWORD));
        assertThat(readCommittedUser().failedLoginCount()).isZero();
    }

    @Test
    void fiveConcurrentWrongPasswordsPersistFiveFailuresWithoutLostUpdates() throws Exception {
        var executor = Executors.newFixedThreadPool(5);
        CountDownLatch ready = new CountDownLatch(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int index = 0; index < 5; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent login start timed out");
                    }
                    DomainException rejected = catchThrowableOfType(DomainException.class,
                            () -> authService.login(new LoginRequest(EMAIL, "wrong-password")));
                    assertThat(rejected).isNotNull();
                    return rejected.getCode();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo(401);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        PersistedUser stored = readCommittedUser();
        assertThat(stored.failedLoginCount()).isEqualTo((short) 5);
        assertThat(stored.status()).isEqualTo(UserStatus.LOCKED);
        assertThat(stored.lockedUntil()).isEqualTo(NOW.plusMinutes(15));
        assertRejected(PASSWORD, 423);
    }

    @Test
    void anUnexpectedJwtFailureRollsBackTheExpiredLockReset() {
        reachLockThreshold();
        expireLock();
        PersistedUser beforeLogin = readCommittedUser();
        when(jwtIssuer.issueAccessToken(any(), any(), any()))
                .thenThrow(new IllegalStateException("JWT signer unavailable"));

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JWT signer unavailable");

        assertThat(readCommittedUser()).isEqualTo(beforeLogin);
    }

    @Test
    void aSoftDeletedUserIsExcludedAndTheStoredCounterIsUnchanged() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User user = userRepository.findById(userId).orElseThrow();
            user.setDeletedAt(NOW.minusDays(1));
            user.setFailedLoginCount((short) 3);
            userRepository.saveAndFlush(user);
        });
        PersistedUser beforeLogin = readCommittedUser();

        assertRejected(PASSWORD, 401);
        assertRejected("wrong-password", 401);

        assertThat(readCommittedUser()).isEqualTo(beforeLogin);
    }

    @Test
    void aDeletedStatusIsRejectedWithoutCountingFailuresOrChangingItToLocked() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User user = userRepository.findById(userId).orElseThrow();
            user.setStatus(UserStatus.DELETED);
            userRepository.saveAndFlush(user);
        });
        PersistedUser beforeLogin = readCommittedUser();

        for (int attempt = 0; attempt < 5; attempt++) {
            assertRejected("wrong-password", 401);
        }
        assertRejected(PASSWORD, 401);

        assertThat(readCommittedUser()).isEqualTo(beforeLogin);
    }

    private void assertRejected(String password, int expectedCode) {
        DomainException rejected = catchThrowableOfType(DomainException.class,
                () -> authService.login(new LoginRequest(EMAIL, password)));
        assertThat(rejected).isNotNull();
        assertThat(rejected.getCode()).isEqualTo(expectedCode);
    }

    private void reachLockThreshold() {
        for (int attempt = 0; attempt < 5; attempt++) {
            assertRejected("wrong-password", 401);
        }
    }

    private void expireLock() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User user = userRepository.findById(userId).orElseThrow();
            user.setLockedUntil(NOW.minusMinutes(1));
            userRepository.saveAndFlush(user);
        });
    }

    // JDBC uses a new connection after login returns/throws: assertions observe committed DB data.
    private PersistedUser readCommittedUser() {
        return jdbc.queryForObject("""
                select failed_login_count, status, locked_until, last_login_at
                from users where id = ?
                """, (row, rowNumber) -> new PersistedUser(
                row.getShort("failed_login_count"),
                UserStatus.valueOf(row.getString("status")),
                row.getObject("locked_until", OffsetDateTime.class),
                row.getObject("last_login_at", OffsetDateTime.class)), userId);
    }

    private record PersistedUser(short failedLoginCount, UserStatus status,
                                 OffsetDateTime lockedUntil, OffsetDateTime lastLoginAt) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    static class JpaTestConfig {

        @Bean
        DataSource dataSource() {
            DriverManagerDataSource source = new DriverManagerDataSource();
            source.setDriverClassName("org.h2.Driver");
            source.setUrl("jdbc:h2:mem:auth-lockout;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            source.setUsername("sa");
            source.setPassword("");
            return source;
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(User.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
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
        IamProperties iamProperties() {
            return new IamProperties();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(INSTANT, ZoneOffset.UTC);
        }

        @Bean
        JwtIssuer jwtIssuer() {
            return mock(JwtIssuer.class);
        }

        @Bean
        AuthService authService(UserRepository repository, PasswordEncoder encoder,
                                JwtIssuer issuer, IamProperties properties, Clock clock) {
            return new AuthService(repository, encoder, issuer, properties, clock);
        }
    }
}
