package com.group5.notificationservice.notification;

import com.group5.notificationservice.shared.config.NotificationAuditingConfiguration;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Validates the merged notification entity against the production PostgreSQL migrations. */
@SpringJUnitConfig(NotificationPersistenceTest.JpaConfig.class)
class NotificationPersistenceTest {
    private static final UUID SYSTEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired private NotificationRepository notifications;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void consumerJpaWritesUseSystemActorAndPreserveHistoricalCreatorOnUpdate() {
        Notification saved = new TransactionTemplate(transactionManager).execute(status -> {
            Notification notification = new Notification();
            notification.setUserId(UUID.randomUUID());
            notification.setType("ORDER_CREATED");
            notification.setTitle("Order placed");
            notification.setContent("Your order was placed");
            return notifications.saveAndFlush(notification);
        });
        assertThat(saved.getCreatedBy()).isEqualTo(SYSTEM_ID);
        assertThat(saved.getUpdatedBy()).isEqualTo(SYSTEM_ID);

        UUID historicalActor = UUID.randomUUID();
        new JdbcTemplate(dataSource).update(
                "UPDATE notifications SET created_by = ?, updated_by = ? WHERE id = ?",
                historicalActor, historicalActor, saved.getId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Notification notification = notifications.findById(saved.getId()).orElseThrow();
            notification.setReadAt(OffsetDateTime.now());
            notifications.flush();
        });

        Notification changed = notifications.findById(saved.getId()).orElseThrow();
        assertThat(changed.getReadAt()).isNotNull();
        assertThat(changed.getCreatedBy()).isEqualTo(historicalActor);
        assertThat(changed.getUpdatedBy()).isEqualTo(SYSTEM_ID);
    }

    @Test
    void rawJdbcConsumerInsertsUseTheSameSystemActorDefaults() {
        UUID id = UUID.randomUUID();
        new JdbcTemplate(dataSource).update("""
                INSERT INTO notifications (id, user_id, type, title, content)
                VALUES (?, ?, 'ORDER_CREATED', 'Order placed', 'Your order was placed')
                """, id, UUID.randomUUID());

        Notification saved = notifications.findById(id).orElseThrow();
        assertThat(saved.getCreatedBy()).isEqualTo(SYSTEM_ID);
        assertThat(saved.getUpdatedBy()).isEqualTo(SYSTEM_ID);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = NotificationRepository.class)
    @Import(NotificationAuditingConfiguration.class)
    static class JpaConfig {
        @Bean
        DataSource dataSource() {
            DataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                    POSTGRES.getUsername(), POSTGRES.getPassword());
            Flyway.configure().dataSource(source).load().migrate();
            return source;
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(Notification.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}
