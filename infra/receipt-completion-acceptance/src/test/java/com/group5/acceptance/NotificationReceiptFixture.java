package com.group5.acceptance;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.common_security.CurrentUserAuditor;
import com.group5.notificationservice.delivery.*;
import com.group5.notificationservice.eventlog.EventLogRecorder;
import com.group5.notificationservice.notification.DomainEventConsumer;
import com.group5.notificationservice.notification.Notification;
import com.group5.notificationservice.notification.NotificationRepository;
import com.group5.notificationservice.shared.config.DeliveryProperties;
import jakarta.persistence.EntityManagerFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Isolated production consumer, durable queue/retry/attempt recorder and SMTP delivery components. */
public final class NotificationReceiptFixture implements AutoCloseable {
    public final SmtpInbox inbox = new SmtpInbox();
    public final AnnotationConfigApplicationContext context;
    public final JdbcTemplate jdbc;
    public final DeliveryRetryJob job;
    public final JavaMailSenderImpl mail;

    public NotificationReceiptFixture() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("receipt-acceptance", Map.of("nexus.notification.email-enabled", "true")));
        context.register(Config.class); context.refresh();
        jdbc = context.getBean(JdbcTemplate.class); job = context.getBean(DeliveryRetryJob.class);
        mail = context.getBean(JavaMailSenderImpl.class); mail.setPort(inbox.port());
    }

    public void receive(EventEnvelope<JsonNode> envelope) {
        context.getBean(DomainEventConsumer.class).receive(new ConsumerRecord<>(Topics.COMMERCE, 0, 0, envelope.aggregateId(), envelope));
    }

    @Override public void close() { context.close(); inbox.close(); }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaAuditing(auditorAwareRef = "auditor")
    @EnableJpaRepositories(basePackageClasses = NotificationRepository.class)
    @Import({DomainEventConsumer.class, EmailChannel.class, DeliveryRepository.class, DeliveryService.class,
            DeliveryEventRecorder.class, EventLogRecorder.class, RetryPolicy.class, EmailSender.class, DeliveryRetryJob.class})
    static class Config {
        @Bean DataSource dataSource() { return AcceptanceDatabase.create("notification-service"); }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean JsonMapper jsonMapper() { return JsonMapper.shared(); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean AuditorAware<UUID> auditor() { return new CurrentUserAuditor(); }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(source);
            factory.setPackagesToScan(Notification.class.getPackageName()); factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate")); return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean IdempotentConsumer idempotentConsumer(EntityManagerFactory factory, PlatformTransactionManager transactions) {
            return new IdempotentConsumer(SharedEntityManagerCreator.createSharedEntityManager(factory), new TransactionTemplate(transactions));
        }
        @Bean DeliveryProperties deliveryProperties() {
            var properties = new DeliveryProperties(); properties.setBaseDelay(Duration.ZERO); properties.setMaxDelay(Duration.ZERO);
            return properties;
        }
        @Bean JavaMailSenderImpl mailSender() {
            var mail = new JavaMailSenderImpl(); mail.setHost("127.0.0.1");
            mail.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "2000");
            mail.getJavaMailProperties().setProperty("mail.smtp.timeout", "2000"); return mail;
        }
        @Bean RecipientDirectory recipients() { return user -> "buyer-" + user + "@example.test"; }
    }
}
