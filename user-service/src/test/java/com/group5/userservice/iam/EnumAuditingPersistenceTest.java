package com.group5.userservice.iam;

import com.group5.common_security.CurrentUser;
import com.group5.common_security.CurrentUserAuditor;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.Gender;
import com.group5.userservice.iam.enums.UserStatus;
import com.group5.userservice.reputation.TrustLevel;
import com.group5.userservice.reputation.UserReputation;
import com.group5.userservice.shared.PostgresAuditingTestConfiguration;
import com.group5.userservice.shared.PostgresTestDatabase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = EnumAuditingPersistenceTest.JpaTestConfig.class)
class EnumAuditingPersistenceTest {
    @Autowired private EntityManagerFactory factory;
    @Autowired private PlatformTransactionManager manager;

    @AfterEach
    void clearActor() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void enumInsertsUpdatesAndReadsUseNativeTypesAndKeepBothActors() {
        UUID creator = UUID.randomUUID();
        UUID editor = UUID.randomUUID();
        actor(creator);
        EntityManager entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        UUID id = transaction.execute(status -> {
            User user = user("enum-audit@nexus.test", "0990000001");
            user.setGender(Gender.FEMALE);
            user.setStatus(UserStatus.LOCKED);
            entityManager.persist(user);
            UserReputation reputation = new UserReputation();
            reputation.setUserId(user.getId());
            reputation.setTrustLevel(TrustLevel.TOP);
            entityManager.persist(reputation);
            entityManager.flush();
            assertThat(user.getCreatedBy()).isEqualTo(creator);
            assertThat(reputation.getUpdatedBy()).isEqualTo(creator);
            return user.getId();
        });
        actor(editor);
        transaction.executeWithoutResult(status -> {
            User user = entityManager.find(User.class, id);
            assertThat(user.getGender()).isEqualTo(Gender.FEMALE);
            assertThat(user.getStatus()).isEqualTo(UserStatus.LOCKED);
            assertThat(entityManager.createQuery("select r from UserReputation r where r.userId = :id", UserReputation.class)
                    .setParameter("id", id).getSingleResult().getTrustLevel()).isEqualTo(TrustLevel.TOP);
            user.setStatus(UserStatus.ACTIVE);
        });
        transaction.executeWithoutResult(status -> {
            User user = entityManager.find(User.class, id);
            assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(user.getCreatedBy()).isEqualTo(creator);
            assertThat(user.getUpdatedBy()).isEqualTo(editor);
        });
    }

    @Test
    void aWriteWithoutAuthenticationUsesTheStableSystemActor() {
        SecurityContextHolder.clearContext();
        EntityManager entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            User user = user("system-audit@nexus.test", "0990000002");
            entityManager.persist(user);
            entityManager.flush();
            assertThat(user.getCreatedBy()).isEqualTo(CurrentUserAuditor.SYSTEM_ID);
            assertThat(user.getUpdatedBy()).isEqualTo(CurrentUserAuditor.SYSTEM_ID);
        });
    }

    private static void actor(UUID id) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(id, Set.of(), Set.of()), null, List.of()));
    }

    private static User user(String email, String phone) {
        User user = new User();
        user.setEmail(email);
        user.setPhone(phone);
        user.setFullName("Audited User");
        user.setPasswordHash("not-a-real-hash");
        return user;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(PostgresAuditingTestConfiguration.class)
    static class JpaTestConfig {
        @Bean
        DataSource dataSource() {
            return PostgresTestDatabase.migratedDataSource("enum_auditing");
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
            bean.setDataSource(dataSource);
            bean.setPackagesToScan("com.group5.userservice");
            bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            bean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return bean;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}
