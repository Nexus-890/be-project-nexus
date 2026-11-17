package com.group5.notificationservice.notification;

import com.group5.common_security.CurrentUser;
import com.group5.common_security.CurrentUserAuditor;
import com.group5.common_security.autoconfigure.JpaAuditingAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Starts the production application and auditing configuration against PostgreSQL migrations. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/notification-persistence-test.properties",
        "spring.cloud.config.enabled=false",
        "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.kafka.bootstrap-servers=127.0.0.1:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "nexus.notification.group-id=notification-persistence-test",
        "nexus.outbox.relay.enabled=false",
        "nexus.security.jwt.issuer=user-service",
        "nexus.security.jwt.audience=nexus-services",
        // Persistence tests do not decode tokens, so this URL is never contacted.
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/.well-known/jwks.json",
        "management.tracing.enabled=false"
})
class NotificationPersistenceTest {
    private static final PostgreSQLContainer POSTGRES = new com.group5.common_test.PostgresTestDatabase("postgres:18-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired private NotificationRepository notifications;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationContext context;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterEach
    void clearActor() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void productionAuditingUsesSystemForConsumersAndCurrentUserForAuthenticatedUpdates() {
        assertThat(context.getBeansOfType(JpaAuditingAutoConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(CurrentUserAuditor.class)).containsOnlyKeys("currentUserAuditor");
        Notification saved = new TransactionTemplate(transactionManager).execute(status -> {
            Notification notification = new Notification();
            notification.setUserId(UUID.randomUUID());
            notification.setType("ORDER_CREATED");
            notification.setTitle("Order placed");
            notification.setContent("Your order was placed");
            return notifications.saveAndFlush(notification);
        });
        assertThat(saved.getCreatedBy()).isEqualTo(CurrentUserAuditor.SYSTEM_ID);
        assertThat(saved.getUpdatedBy()).isEqualTo(CurrentUserAuditor.SYSTEM_ID);

        UUID historicalActor = UUID.randomUUID();
        new JdbcTemplate(dataSource).update(
                "UPDATE notifications SET created_by = ?, updated_by = ? WHERE id = ?",
                historicalActor, historicalActor, saved.getId());
        UUID editor = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new CurrentUser(editor, Set.of(), Set.of()), "", List.of()));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Notification notification = notifications.findById(saved.getId()).orElseThrow();
            notification.setReadAt(OffsetDateTime.now());
            notifications.flush();
        });

        Notification changed = notifications.findById(saved.getId()).orElseThrow();
        assertThat(changed.getReadAt()).isNotNull();
        assertThat(changed.getCreatedBy()).isEqualTo(historicalActor);
        assertThat(changed.getUpdatedBy()).isEqualTo(editor);

        SecurityContextHolder.clearContext();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Notification notification = notifications.findById(saved.getId()).orElseThrow();
            notification.setTitle("Updated by consumer");
            notifications.flush();
        });
        Notification systemUpdate = notifications.findById(saved.getId()).orElseThrow();
        assertThat(systemUpdate.getCreatedBy()).isEqualTo(historicalActor);
        assertThat(systemUpdate.getUpdatedBy()).isEqualTo(CurrentUserAuditor.SYSTEM_ID);
    }

    @Test
    void rawJdbcConsumerInsertsUseTheSameSystemActorDefaults() {
        UUID id = UUID.randomUUID();
        new JdbcTemplate(dataSource).update("""
                INSERT INTO notifications (id, user_id, type, title, content)
                VALUES (?, ?, 'ORDER_CREATED', 'Order placed', 'Your order was placed')
                """, id, UUID.randomUUID());

        Notification saved = notifications.findById(id).orElseThrow();
        assertThat(saved.getCreatedBy()).isEqualTo(CurrentUserAuditor.SYSTEM_ID);
        assertThat(saved.getUpdatedBy()).isEqualTo(CurrentUserAuditor.SYSTEM_ID);
    }
}
