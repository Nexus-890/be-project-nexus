package com.group5.acceptance;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUserAuditor;
import com.group5.shipmentservice.shared.config.ShippingProperties;
import com.group5.shipmentservice.shipping.adapter.CarrierAdapter;
import com.group5.shipmentservice.shipping.adapter.CarrierRegistry;
import com.group5.shipmentservice.shipping.adapter.model.PickupAddress;
import com.group5.shipmentservice.shipping.entity.Shipment;
import com.group5.shipmentservice.shipping.event.OrderPaidShipmentConsumer;
import com.group5.shipmentservice.shipping.event.ShipmentEventPublisher;
import com.group5.shipmentservice.shipping.event.payload.OrderPaidSnapshot;
import com.group5.shipmentservice.shipping.repository.ShipmentRepository;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import com.group5.shipmentservice.shipping.service.PaidOrderSnapshotService;
import com.group5.shipmentservice.shipping.service.ShipmentCreationService;
import jakarta.persistence.EntityManagerFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
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
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;

/** Shipment persistence, migrations, consumer and worker use their own database. */
final class ShipmentPaidFixture implements AutoCloseable {
    static final UUID WAREHOUSE = UUID.fromString("25800000-0000-4000-8000-000000000099");
    final AnnotationConfigApplicationContext context;
    final JdbcTemplate jdbc;
    final ShipmentCreationService creations;

    ShipmentPaidFixture(ShippingProperties.CarrierMode booking, List<CarrierAdapter> adapters) {
        this(properties(booking), adapters);
    }

    ShipmentPaidFixture(ShippingProperties properties, List<CarrierAdapter> adapters) {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(ShippingProperties.class, () -> properties);
        context.registerBean(CarrierRegistry.class, () -> new CarrierRegistry(adapters, properties));
        context.register(Config.class);
        context.refresh();
        jdbc = context.getBean(JdbcTemplate.class);
        creations = context.getBean(ShipmentCreationService.class);
    }

    static ShippingProperties properties(ShippingProperties.CarrierMode booking) {
        return new ShippingProperties(ShippingProperties.CarrierMode.NONE, 4000, 0, 0,
                BigDecimal.valueOf(1_000_000), 5, 30000, "", WAREHOUSE,
                new PickupAddress("1000001", "1000002", "20 Sample Warehouse Street"), Map.of(),
                ShippingProperties.CarrierMode.GHN, booking);
    }

    void receive(EventEnvelope<JsonNode> event) {
        context.getBean(OrderPaidShipmentConsumer.class).receive(
                new ConsumerRecord<>(Topics.COMMERCE, 0, 0, event.aggregateId(), event));
    }

    void commitBeforeInitialBooking(EventEnvelope<JsonNode> event) {
        // Simulate the consumer process stopping after its committed event claim/creation, before booking.
        var snapshot = OrderPaidSnapshot.parse(event.payload(), JsonMapper.shared());
        context.getBean(IdempotentConsumer.class).handle(OrderPaidShipmentConsumer.CONSUMER, event,
                ignored -> creations.createPaid(event, snapshot));
    }

    UUID id() { return jdbc.queryForObject("SELECT id FROM shipments", UUID.class); }
    String tracking() { return jdbc.queryForObject("SELECT tracking_code FROM shipments", String.class); }
    String status() { return jdbc.queryForObject("SELECT shipment_status::text FROM shipments", String.class); }
    JsonNode snapshot() { return JsonMapper.shared().readTree(jdbc.queryForObject("SELECT shipping_info::text FROM shipments", String.class)); }
    long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    void runDueWorker() {
        jdbc.update("""
                UPDATE shipments SET next_tracking_retry_at=now()-interval '1 second'
                WHERE tracking_code IS NULL AND next_tracking_retry_at IS NOT NULL
                """);
        creations.retryDueTracking();
    }
    @Override public void close() { context.close(); }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaAuditing(auditorAwareRef = "auditor")
    @EnableJpaRepositories(basePackageClasses = ShipmentRepository.class)
    @Import({ShipmentCreationService.class, OrderPaidShipmentConsumer.class, ShipmentEventPublisher.class})
    static class Config {
        @Bean DataSource dataSource() { return AcceptanceDatabase.create("shipment-service"); }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean JsonMapper mapper() { return JsonMapper.shared(); }
        @Bean AuditorAware<UUID> auditor() { return new CurrentUserAuditor(); }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source); factory.setPackagesToScan(Shipment.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate")); return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean IdempotentConsumer idempotency(EntityManagerFactory factory, PlatformTransactionManager transactions) {
            return new IdempotentConsumer(SharedEntityManagerCreator.createSharedEntityManager(factory), new TransactionTemplate(transactions));
        }
        @Bean OutboxWriter outbox(JdbcTemplate jdbc, JsonMapper mapper, PlatformTransactionManager transactions) {
            return new OutboxWriter(jdbc, mapper, transactions);
        }
        @Bean EventEnvelopeFactory envelopes() {
            return new EventEnvelopeFactory(new CommerceReceiptFixture.Config().tracer(), Clock.systemUTC());
        }
        @Bean CarrierExecutor carrierExecutor(ShippingProperties properties) { return new CarrierExecutor(properties); }
        @Bean PaidOrderSnapshotService paidOrderSnapshotService() { return mock(PaidOrderSnapshotService.class); }
    }
}
