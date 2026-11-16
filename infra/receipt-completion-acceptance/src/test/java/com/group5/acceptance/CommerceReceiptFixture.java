package com.group5.acceptance;

import com.group5.commerceservice.order.OrderCompletionJob;
import com.group5.commerceservice.order.OrderCompletionService;
import com.group5.commerceservice.order.OrderReceiptRenderer;
import com.group5.commerceservice.order.OrderStatus;
import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.event.OrderEventPublisher;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.service.OrderStateMachine;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import com.group5.common_security.CurrentUserAuditor;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityManagerFactory;
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
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real commerce persistence, completion policy, receipt rendering and transactional outbox. */
public final class CommerceReceiptFixture implements AutoCloseable {
    public final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class);
    public final JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
    public final OrderCompletionService completions = context.getBean(OrderCompletionService.class);
    public final OrderCompletionJob job = context.getBean(OrderCompletionJob.class);

    public OrderFixture order() {
        return order("0.00");
    }

    public OrderFixture order(String shippingAmount) {
        UUID buyer = UUID.randomUUID(), seller = UUID.randomUUID();
        BigDecimal shipping = new BigDecimal(shippingAmount);
        BigDecimal amount = new BigDecimal("300000.00").add(shipping);
        String number = "NX-ACCEPT-" + UUID.randomUUID().toString().substring(0, 20);
        UUID group = jdbc.queryForObject("""
                INSERT INTO order_groups(buyer_id,idempotency_key,total_amount,due_at)
                VALUES (?,?,?,now()+interval '15 minutes') RETURNING id
                """, UUID.class, buyer, UUID.randomUUID().toString(), amount);
        UUID id = jdbc.queryForObject("""
                INSERT INTO orders(order_group_id,user_id,seller_id,order_number,order_type,status,subtotal,shipping_fee,total_amount,idempotency_key)
                VALUES (?,?,?,?,'STANDARD','SHIPPED',300000,?,?,?) RETURNING id
                """, UUID.class, group, buyer, seller, number, shipping, amount, UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO order_items(order_id,product_id,sku_id,product_name,sku_code,quantity,unit_price,total_price)
                VALUES (?,?,?,'Cà phê <script>private</script> & tea','RECEIPT-SKU',2,150000,300000)
                """, id, UUID.randomUUID(), UUID.randomUUID());
        context.getBean(OrderStateMachine.class).move(id, OrderStatus.DELIVERED, buyer, "Shipment delivered");
        return new OrderFixture(id, group, buyer, seller, number, amount, shipping);
    }

    public List<EventEnvelope<JsonNode>> events(UUID order) {
        return jdbc.queryForList("SELECT payload::text FROM outbox WHERE aggregate_id=? AND event_type='OrderCompleted' ORDER BY occurred_at,id", String.class, order.toString())
                .stream().map(raw -> {
                    JsonNode event = JsonMapper.shared().readTree(raw);
                    return new EventEnvelope<>(UUID.fromString(event.path("eventId").asString()), event.path("eventType").asString(),
                            event.path("aggregateId").asString(), Instant.parse(event.path("occurredAt").asString()),
                            event.path("traceId").asString(), event.path("payload"));
                }).toList();
    }

    public void due(OrderFixture order) {
        jdbc.update("UPDATE orders SET delivered_at=now()-interval '8 days',auto_complete_at=now()-interval '1 day' WHERE id=?", order.id());
    }

    public String status(OrderFixture order) { return jdbc.queryForObject("SELECT status::text FROM orders WHERE id=?", String.class, order.id()); }
    @Override public void close() { context.close(); }

    public record OrderFixture(UUID id, UUID groupId, UUID buyerId, UUID sellerId, String number, BigDecimal amount, BigDecimal shipping) {
        public CurrentUser buyer() { return new CurrentUser(buyerId, Set.of("BUYER"), Set.of("ORDER.VIEW")); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaAuditing(auditorAwareRef = "auditor")
    @EnableJpaRepositories(basePackageClasses = OrderRepository.class)
    @Import({OrderStateMachine.class, OrderReceiptRenderer.class, OrderCompletionService.class, OrderCompletionJob.class})
    static class Config {
        @Bean DataSource dataSource() { return AcceptanceDatabase.create("commerce-service"); }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean AuditorAware<UUID> auditor() { return new CurrentUserAuditor(); }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(source);
            factory.setPackagesToScan(Order.class.getPackageName()); factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate")); return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean OrderEventPublisher publisher(DataSource source, PlatformTransactionManager transactionManager, Tracer tracer) {
            return new OrderEventPublisher(new OutboxWriter(new JdbcTemplate(source), JsonMapper.shared(), transactionManager), new EventEnvelopeFactory(tracer, Clock.systemUTC()));
        }
        @Bean Tracer tracer() {
            var tracer = mock(Tracer.class); var span = mock(Span.class, RETURNS_SELF); var trace = mock(TraceContext.class);
            when(trace.traceId()).thenReturn("4bf92f3577b34da6a3ce929d0e0e4736"); when(span.context()).thenReturn(trace);
            when(tracer.currentSpan()).thenReturn(span); when(tracer.nextSpan()).thenReturn(span);
            when(tracer.withSpan(any())).thenReturn(() -> { }); return tracer;
        }
    }
}
