package com.group5.auctionservice.settlement;

import com.group5.auctionservice.auction.*;
import com.group5.common_events.*;
import com.group5.common_outbox.*;
import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityManagerFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(SettlementStatusUpdaterTest.Config.class)
class SettlementStatusUpdaterTest {
    static final JsonMapper JSON=JsonMapper.shared();
    @Autowired SettlementStatusUpdater consumer;
    @Autowired DataSource source;
    JdbcTemplate jdbc() { return new JdbcTemplate(source); }

    @Test void settlesWonAndPublishesOnceForDuplicateOrNewEnvelope() throws Exception {
        UUID auction=auction("WON"), order=UUID.randomUUID();
        var steps = new ArrayList<Map<String, Object>>();
        steps.add(settlementState(auction, "Before OrderCreated"));
        var event=record(auction,order,"AUCTION");
        consumer.receive(event);
        steps.add(settlementState(auction, "OrderCreated consumed"));
        consumer.receive(event); consumer.receive(record(auction,order,"AUCTION"));
        assertThat(jdbc().queryForObject("SELECT status::text FROM auctions WHERE id=?",String.class,auction)).isEqualTo("SETTLED");
        assertThat(jdbc().queryForObject("SELECT order_id FROM auctions WHERE id=?",UUID.class,auction)).isEqualTo(order);
        assertThat(jdbc().queryForObject("SELECT settled_at IS NOT NULL FROM auctions WHERE id=?",Boolean.class,auction)).isTrue();
        assertThat(events(auction)).isEqualTo(1);
        var payload=JSON.readTree(jdbc().queryForObject("SELECT payload::text FROM outbox WHERE aggregate_id=?",String.class,auction.toString()));
        assertThat(payload.path("payload").path("orderId").asString()).isEqualTo(order.toString());
        steps.add(settlementState(auction, "Same and new eventId replayed"));
        if (Boolean.getBoolean("settlement.capture")) {
            var path = java.nio.file.Path.of("target/settlement-evidence/auction-states.json");
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(steps));
        }
    }

    private Map<String, Object> settlementState(UUID auction, String step) {
        Map<String, Object> state = new LinkedHashMap<>(jdbc().queryForMap("""
                SELECT id::text AS auction_id,status::text,order_id::text,settled_at::text,
                       (SELECT count(*) FROM outbox WHERE aggregate_id=auctions.id::text AND event_type='AuctionSettled') AS settled_events
                FROM auctions WHERE id=?
                """,auction));
        state.put("step", step);
        return state;
    }

    @Test void ignoresStandardAndNonWon() {
        UUID auction=auction("WON"); consumer.receive(record(auction,UUID.randomUUID(),"STANDARD"));
        assertThat(events(auction)).isZero();
        assertThat(jdbc().queryForObject("SELECT status::text FROM auctions WHERE id=?",String.class,auction)).isEqualTo("WON");
        UUID active=auction("ACTIVE"); consumer.receive(record(active,UUID.randomUUID(),"AUCTION"));
        assertThat(events(active)).isZero();
        assertThat(jdbc().queryForObject("SELECT status::text FROM auctions WHERE id=?",String.class,active)).isEqualTo("ACTIVE");
    }

    @Test void failedOutboxRollsBackStateAndProcessedMarker() {
        UUID auction=auction("WON"); var message=record(auction,UUID.randomUUID(),"AUCTION");
        jdbc().execute("ALTER TABLE outbox ADD CONSTRAINT fail_settlement CHECK(event_type <> 'AuctionSettled') NOT VALID");
        try { assertThatThrownBy(() -> consumer.receive(message)).isInstanceOf(RuntimeException.class); }
        finally { jdbc().execute("ALTER TABLE outbox DROP CONSTRAINT fail_settlement"); }
        assertThat(jdbc().queryForObject("SELECT status::text FROM auctions WHERE id=?",String.class,auction)).isEqualTo("WON");
        assertThat(jdbc().queryForObject("SELECT order_id IS NULL AND settled_at IS NULL FROM auctions WHERE id=?",Boolean.class,auction)).isTrue();
        assertThat(jdbc().queryForObject("SELECT count(*) FROM processed_events WHERE event_id=?",Long.class,message.value().eventId())).isZero();
        consumer.receive(message); assertThat(events(auction)).isEqualTo(1);
    }

    @Test void concurrentOrderEventsSettleOnce() throws Exception {
        UUID auction=auction("WON"), order=UUID.randomUUID();
        try (var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1); var futures=new ArrayList<Future<?>>();
            for (int i=0;i<2;i++) futures.add(pool.submit(() -> { try { start.await(); consumer.receive(record(auction,order,"AUCTION")); } catch (InterruptedException e) { throw new RuntimeException(e); } }));
            start.countDown(); for (var future:futures) future.get(15,TimeUnit.SECONDS);
        }
        assertThat(events(auction)).isEqualTo(1);
    }

    long events(UUID auction) { return jdbc().queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id=? AND event_type='AuctionSettled'",Long.class,auction.toString()); }
    UUID auction(String status) {
        UUID id=UUID.randomUUID();
        jdbc().update("""
                INSERT INTO auctions(id,product_id,sku_id,seller_id,title,starting_price,bid_increment,current_price,
                start_at,end_at,original_end_at,status,winner_id,final_price,payment_due_at)
                VALUES(?,?,?,?, 'Auction',100,10,200,now()-interval '2 days',now()-interval '1 day',now()-interval '1 day',CAST(? AS auction_status),?,200,now()+interval '1 day')
                """,id,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),status,UUID.randomUUID()); return id;
    }
    static ConsumerRecord<String,EventEnvelope<JsonNode>> record(UUID auction,UUID order,String type) {
        var payload=Map.of("orderId",order,"orderType",type,"referenceId",auction,"buyerId",UUID.randomUUID(),"productIds",List.of(UUID.randomUUID()));
        return new ConsumerRecord<>(Topics.COMMERCE,0,0,order.toString(),new EventEnvelope<>(UUID.randomUUID(),"OrderCreated",order.toString(),Instant.now(),"172settle",JSON.valueToTree(payload)));
    }

    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses=AuctionRepository.class)
    @Import({SettlementStatusUpdater.class,AuctionStateMachine.class})
    static class Config {
        @Bean DataSource dataSource() {
            String url=System.getProperty("settlement.test.jdbc-url"),user="postgres",password="";
            if(url==null) { var pg=new PostgreSQLContainer("postgres:18-alpine");pg.start();url=pg.getJdbcUrl();user=pg.getUsername();password=pg.getPassword(); }
            String schema="auction_settlement_"+UUID.randomUUID().toString().replace("-","");
            var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();return source;
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory=new LocalContainerEntityManagerFactoryBean();factory.setDataSource(source);
            factory.setPackagesToScan(Auction.class.getPackageName());factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","validate",
                    "hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean IdempotentConsumer consumer(EntityManagerFactory factory,PlatformTransactionManager tx) { return new IdempotentConsumer(SharedEntityManagerCreator.createSharedEntityManager(factory),new TransactionTemplate(tx)); }
        @Bean JsonMapper mapper() { return JSON; }
        @Bean Tracer tracer() { var tracer=mock(Tracer.class,RETURNS_DEEP_STUBS);when(tracer.currentSpan().context().traceId()).thenReturn("172settle");return tracer; }
        @Bean OutboxWriter outbox(DataSource source,PlatformTransactionManager tx) { return new OutboxWriter(new JdbcTemplate(source),JSON,tx); }
        @Bean EventEnvelopeFactory envelopes(Tracer tracer) { return new EventEnvelopeFactory(tracer,Clock.systemUTC()); }
    }
}
