package com.group5.userservice.phone;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.iam.UserRepository;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.Gender;
import com.group5.userservice.phone.dto.PhoneCodeSentResponse;
import com.group5.userservice.phone.dto.PhoneVerifiedResponse;
import com.group5.userservice.shared.PostgresAuditingTestConfiguration;
import com.group5.userservice.shared.PostgresTestDatabase;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = PhoneVerificationServiceTest.JpaTestConfig.class)
class PhoneVerificationServiceTest {

    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");
    private static final MutableClock CLOCK = new MutableClock();
    private static final List<String> SENT = new ArrayList<>();

    @Autowired
    private PhoneVerificationService service;
    @Autowired
    private UserRepository users;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private UUID userId;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from phone_verifications");
        jdbc.update("delete from user_addresses");
        jdbc.update("delete from users");
        CLOCK.now = Instant.parse("2026-11-14T08:00:00Z");
        SENT.clear();
        userId = seedUser("+84901234567");
    }

    @Test
    void theRightCodeVerifiesThePhoneAndOnlyAHashIsStored() {
        PhoneCodeSentResponse sent = service.send(userId);
        String code = lastCode();

        assertThat(sent.phone()).isEqualTo("+84901234567");
        assertThat(sent.expiresAt().toInstant()).isEqualTo(CLOCK.now.plus(Duration.ofMinutes(5)));
        Map<String, Object> row = jdbc.queryForMap("select id, code_hash from phone_verifications");
        assertThat((String) row.get("code_hash")).hasSize(64).doesNotContain(code)
                .isEqualTo(PhoneVerificationService.hash((UUID) row.get("id"), code));

        PhoneVerifiedResponse verified = service.confirm(userId, code);

        assertThat(verified.phone()).isEqualTo("+84901234567");
        assertThat(jdbc.queryForObject("select phone_verified_at is not null from users", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select verified_at is not null from phone_verifications", Boolean.class)).isTrue();
        assertThat(errorOf(() -> service.confirm(userId, code))).isEqualTo("PHONE_CODE_INVALID");
    }

    @Test
    void wrongCodesCountAndTheSixthTryIsRefusedEvenWithTheRightCode() {
        service.send(userId);
        String code = lastCode();
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(errorOf(() -> service.confirm(userId, wrong))).isEqualTo("PHONE_CODE_INVALID");
        }

        assertThat(jdbc.queryForObject("select attempts from phone_verifications", Integer.class)).isEqualTo(5);
        assertThat(errorOf(() -> service.confirm(userId, code))).isEqualTo("PHONE_CODE_RATE_LIMITED");
        assertThat(jdbc.queryForObject("select phone_verified_at is null from users", Boolean.class)).isTrue();
    }

    @Test
    void theFourthSendInFifteenMinutesIsRefused() {
        service.send(userId);
        service.send(userId);
        service.send(userId);

        assertThat(errorOf(() -> service.send(userId))).isEqualTo("PHONE_CODE_RATE_LIMITED");
        assertThat(SENT).hasSize(3);

        CLOCK.now = CLOCK.now.plus(Duration.ofMinutes(16));
        service.send(userId);
        assertThat(SENT).hasSize(4);
    }

    @Test
    void anExpiredCodeIsRejected() {
        service.send(userId);
        String code = lastCode();
        CLOCK.now = CLOCK.now.plus(Duration.ofMinutes(5));

        assertThat(errorOf(() -> service.confirm(userId, code))).isEqualTo("PHONE_CODE_EXPIRED");
    }

    @Test
    void onlyTheNewestCodeCounts() {
        service.send(userId);
        String first = lastCode();
        CLOCK.now = CLOCK.now.plusSeconds(1);
        service.send(userId);
        String second = lastCode();

        if (!first.equals(second)) {
            assertThat(errorOf(() -> service.confirm(userId, first))).isEqualTo("PHONE_CODE_INVALID");
        }
        assertThat(service.confirm(userId, second).phone()).isEqualTo("+84901234567");
    }

    @Test
    void aCodeSentToTheOldPhoneDoesNotVerifyANewPhone() {
        service.send(userId);
        String code = lastCode();
        jdbc.update("update users set phone = '+84907654321'");

        assertThat(errorOf(() -> service.confirm(userId, code))).isEqualTo("PHONE_CODE_INVALID");
        assertThat(jdbc.queryForObject("select phone_verified_at is null from users", Boolean.class)).isTrue();
    }

    @Test
    void anUnknownUserGets404() {
        assertThat(errorOf(() -> service.send(UUID.randomUUID()))).isEqualTo("USER_NOT_FOUND");
    }

    private String lastCode() {
        Matcher matcher = CODE.matcher(SENT.getLast());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String errorOf(Runnable action) {
        DomainException error = catchThrowableOfType(DomainException.class, action::run);
        assertThat(error).isNotNull();
        return error.getErrorCode();
    }

    private UUID seedUser(String phone) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("phone@nexus.local");
        user.setPhone(phone);
        user.setFullName("Phone User");
        user.setIdentityNumber("ID-PHONE");
        user.setDateOfBirth(LocalDate.of(2000, 1, 2));
        user.setGender(Gender.MALE);
        user.setAddress("Phone City");
        return users.saveAndFlush(user).getId();
    }

    static final class MutableClock extends Clock {
        volatile Instant now = Instant.EPOCH;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Import(PostgresAuditingTestConfiguration.class)
    @EnableJpaRepositories(basePackageClasses = {UserRepository.class, PhoneVerificationRepository.class})
    static class JpaTestConfig {

        @Bean
        DataSource dataSource() {
            return PostgresTestDatabase.migratedDataSource("phone_verification");
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(User.class.getPackageName(), PhoneVerification.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        PhoneVerificationService phoneVerificationService(UserRepository users,
                                                          PhoneVerificationRepository verifications,
                                                          PlatformTransactionManager manager) {
            return new PhoneVerificationService(users, verifications, (phone, text) -> SENT.add(text), CLOCK, manager);
        }
    }
}
