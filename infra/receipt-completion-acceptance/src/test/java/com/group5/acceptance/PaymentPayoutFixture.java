package com.group5.acceptance;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.common_outbox.OutboxWriter;
import com.group5.paymentservice.payout.PayoutGateway;
import com.group5.paymentservice.payout.PayoutProperties;
import com.group5.paymentservice.payout.PayoutService;
import com.group5.paymentservice.payout.PayoutRepository;
import com.group5.paymentservice.payout.PayoutHistoryRepository;
import com.group5.paymentservice.payout.PayoutMovementRepository;
import com.group5.paymentservice.payment.repository.PayoutEligibilityRepository;
import com.group5.paymentservice.payout.SandboxPayoutGateway;
import io.micrometer.tracing.Tracer;
import com.group5.paymentservice.payment.entity.PaymentInstruction;
import com.group5.paymentservice.payment.event.PayoutEligibilityConsumer;
import com.group5.paymentservice.payment.repository.PaymentSessionRepository;
import com.group5.paymentservice.payment.service.PayoutEligibilityService;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.Locale;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

public final class PaymentPayoutFixture implements AutoCloseable {
    public final AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext(Config.class);
    public final JdbcTemplate jdbc=context.getBean(JdbcTemplate.class);

    public void fund(CommerceReceiptFixture.OrderFixture order) {
        UUID session=jdbc.queryForObject("""
                INSERT INTO payment_sessions(order_group_id,buyer_id,total_amount,currency,memo_code,due_at,status,
                                             source_event_id,idempotency_key,paid_at)
                VALUES (?,?,?,'VND',?,now()+interval '1 hour','PAID',?,?,now()-interval '1 hour') RETURNING id
                """,UUID.class,order.groupId(),order.buyerId(),order.amount(),order.groupId().toString().replace("-","").toUpperCase(Locale.ROOT),UUID.randomUUID(),UUID.randomUUID().toString());
        UUID receipt=jdbc.queryForObject("""
                INSERT INTO payment_transactions(session_id,provider,txn_reference,amount,currency,status,type,idempotency_key,received_at)
                VALUES (?,'SEPAY',?,?,'VND','SUCCESS','NORMAL',?,now()-interval '1 hour') RETURNING id
                """,UUID.class,session,UUID.randomUUID().toString(),order.amount(),UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO payment_instructions(order_id,session_id,funded_transaction_id,buyer_id,seller_id,amount,currency,
                                                 due_at,status,paid_at,last_order_version)
                VALUES (?,?,?,?,?,?,'VND',now()+interval '1 hour','PAID',now()-interval '1 hour',1)
                """,order.id(),session,receipt,order.buyerId(),order.sellerId(),order.amount());
    }
    public void receive(EventEnvelope<JsonNode> event) {
        context.getBean(PayoutEligibilityConsumer.class).receive(new ConsumerRecord<>(Topics.COMMERCE,0,0,event.aggregateId(),event));
    }
    public long eligible(UUID order) { return jdbc.queryForObject("SELECT count(*) FROM payout_eligibility WHERE order_id=?",Long.class,order); }
    public long payouts(UUID order) { return jdbc.queryForObject("SELECT count(*) FROM payouts WHERE order_id=?",Long.class,order); }
    public Map<String,Object> payout(UUID order) { return jdbc.queryForMap("SELECT * FROM payouts WHERE order_id=?",order); }
    @Override public void close() { context.close(); }

    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses=PaymentSessionRepository.class)
    @Import({PayoutEligibilityConsumer.class, PayoutEligibilityService.class, PayoutEligibilityRepository.class,
            PayoutService.class, PayoutRepository.class, PayoutHistoryRepository.class, PayoutMovementRepository.class})
    static class Config {
        @Bean DataSource dataSource() { return AcceptanceDatabase.create("payment-service"); }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean JsonMapper mapper() { return JsonMapper.shared(); }
        @Bean Validator validator() { return Validation.buildDefaultValidatorFactory().getValidator(); }
        @Bean PayoutProperties payoutProperties() { return new PayoutProperties(); }
        @Bean PayoutGateway payoutGateway(PayoutProperties properties) { return new SandboxPayoutGateway(properties); }
        @Bean Tracer tracer() { return Tracer.NOOP; }
        @Bean EventEnvelopeFactory envelopes(Tracer tracer) { return new EventEnvelopeFactory(tracer, Clock.systemUTC()); }
        @Bean OutboxWriter outbox(JdbcTemplate jdbc, JsonMapper mapper, PlatformTransactionManager transactions) {
            return new OutboxWriter(jdbc,mapper,transactions);
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory=new LocalContainerEntityManagerFactoryBean();factory.setDataSource(source);
            factory.setPackagesToScan(PaymentInstruction.class.getPackageName());factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","validate"));return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean IdempotentConsumer consumer(EntityManagerFactory factory,PlatformTransactionManager transactions) {
            return new IdempotentConsumer(SharedEntityManagerCreator.createSharedEntityManager(factory),new TransactionTemplate(transactions));
        }
    }
}
