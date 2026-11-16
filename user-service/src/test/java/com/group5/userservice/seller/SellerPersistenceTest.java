package com.group5.userservice.seller;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.common_security.CurrentUserAuditor;
import com.group5.userservice.iam.UserRepository;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.shared.PostgresAuditingTestConfiguration;
import com.group5.userservice.shared.PostgresTestDatabase;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SellerPersistenceTest.Config.class)
class SellerPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-11-16T04:00:00Z");
    @Autowired SellerService service;
    @Autowired DataSource dataSource;
    JdbcTemplate jdbc;
    UUID id;

    @BeforeEach
    void seed() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM seller");
        jdbc.update("DELETE FROM users");
        id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,phone,full_name) VALUES (?,?,?,?)",
                id, "seller@nexus.test", "0900000001", "Existing Seller");
    }

    @Test
    void existingSellerGetsOneActiveRowWithTheExistingIdentityAndSystemReview() {
        var response = service.getMe(seller());
        assertThat(response.userId()).isEqualTo(id);
        assertThat(response.companyName()).isEqualTo("Existing Seller");
        assertThat(response.contactPhone()).isEqualTo("0900000001");
        assertThat(response.status()).isEqualTo(SellerStatus.ACTIVE);
        assertThat(response.reviewedBy()).isEqualTo(CurrentUserAuditor.SYSTEM_ID);
        assertThat(response.reviewedAt().toInstant()).isEqualTo(NOW);
        assertThat(service.getMe(seller())).isEqualTo(response);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seller", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentFirstReadsSerializeWithoutDuplicateRowsOrFailures() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.getMe(seller()));
            var second = executor.submit(() -> service.getMe(seller()));
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seller", Long.class)).isEqualTo(1);
    }

    @Test
    void profileUpdateChangesOnlyItsTwoFieldsAndRetainsApplicationDecision() {
        jdbc.update("""
                INSERT INTO seller(user_id,company_name,contact_phone,status,application_note,rejection_reason,
                    reviewed_by,reviewed_at) VALUES (?,?,?,'REJECTED','application','missing document',?,now())
                """, id, "Old Company", "0900000001", UUID.randomUUID());
        var before = service.getMe(seller());
        var after = service.updateMe(seller(), new SellerProfileRequest("  New Company  ", "  0900000002  "));
        assertThat(after.companyName()).isEqualTo("New Company");
        assertThat(after.contactPhone()).isEqualTo("0900000002");
        assertThat(after.status()).isEqualTo(SellerStatus.REJECTED);
        assertThat(after.applicationNote()).isEqualTo(before.applicationNote());
        assertThat(after.rejectionReason()).isEqualTo(before.rejectionReason());
        assertThat(after.reviewedBy()).isEqualTo(before.reviewedBy());
        assertThat(after.appliedAt()).isEqualTo(before.appliedAt());
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(jdbc.queryForObject("SELECT phone FROM users WHERE id=?", String.class, id)).isEqualTo("0900000001");
    }

    @Test
    void pendingApplicationIsNeverPromotedByReadingTheProfile() {
        jdbc.update("INSERT INTO seller(user_id,company_name,contact_phone,application_note) VALUES (?,?,?,?)",
                id, "Applying Company", "0900000001", "Please approve");
        assertThat(service.getMe(seller()).status()).isEqualTo(SellerStatus.PENDING);
    }

    @Test
    void unauthorizedMissingAndDeletedUsersCannotCreateSellerRows() {
        assertDomain(401, () -> service.getMe(null));
        assertDomain(403, () -> service.getMe(new CurrentUser(id, Set.of("BUYER"), Set.of())));
        assertDomain(404, () -> service.getMe(new CurrentUser(UUID.randomUUID(), Set.of("SELLER"), Set.of())));
        jdbc.update("UPDATE users SET deleted_at=now() WHERE id=?", id);
        assertDomain(404, () -> service.getMe(seller()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seller", Long.class)).isZero();
    }

    @Test
    void databaseRequiresDecisionMetadataAndNonemptyRejectionReason() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO seller(user_id,company_name,contact_phone,status) VALUES (?,?,?,'ACTIVE')
                """, id, "Company", "0900000001")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO seller(user_id,company_name,contact_phone,status,reviewed_by,reviewed_at,rejection_reason)
                VALUES (?,?,?,'REJECTED',?,now(),'  ')
                """, id, "Company", "0900000001", UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
    }

    private CurrentUser seller() { return new CurrentUser(id, Set.of("SELLER"), Set.of()); }
    private static void assertDomain(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.getCode()).isEqualTo(status));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(PostgresAuditingTestConfiguration.class)
    @EnableJpaRepositories(basePackageClasses = {UserRepository.class, SellerRepository.class})
    static class Config {
        @Bean DataSource dataSource() { return PostgresTestDatabase.migratedDataSource("seller_profile"); }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var bean = new LocalContainerEntityManagerFactoryBean();
            bean.setDataSource(source);
            bean.setPackagesToScan(User.class.getPackageName(), Seller.class.getPackageName());
            bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            bean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return bean;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
        @Bean SellerService service(UserRepository users, SellerRepository sellers) {
            return new SellerService(users, sellers, Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }
}
