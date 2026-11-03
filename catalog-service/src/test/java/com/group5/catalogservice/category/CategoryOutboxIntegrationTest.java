package com.group5.catalogservice.category;

import com.group5.catalogservice.category.dto.CategoryRequest;
import com.group5.catalogservice.category.dto.CategoryUpdateRequest;
import com.group5.catalogservice.shared.config.CatalogProperties;
import com.group5.catalogservice.shared.validation.SlugValidation;
import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.EventKafka;
import com.group5.common_events.Topics;
import com.group5.common_events.autoconfigure.EventsAutoConfiguration;
import com.group5.common_outbox.OutboxRelay;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_outbox.autoconfigure.OutboxAutoConfiguration;
import com.group5.common_security.autoconfigure.JpaAuditingAutoConfiguration;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityManagerFactory;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.kafka.KafkaContainer;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real JPA/JDBC transactions and Kafka transport; only the active trace is a fixture. */
class CategoryOutboxIntegrationTest {

    private static final GenericContainer<?> POSTGRES =
            new GenericContainer<>(DockerImageName.parse("postgres:18"))
                    .withEnv("POSTGRES_PASSWORD", "test")
                    .withExposedPorts(5432)
                    .waitingFor(Wait.forListeningPort());

    // Docker can reassign an ephemeral host port on restart. Keep the advertised
    // endpoint stable across the outage test while still choosing a free test port.
    private static final int KAFKA_PORT = availablePort();
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", KAFKA_PORT), new ExposedPort(9092))));
    private static final JsonMapper MAPPER = JsonMapper.shared();

    private static TimeZone originalTimeZone;

    @BeforeAll
    static void startPostgres() {
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        POSTGRES.start();
        KAFKA.start();
    }

    @AfterAll
    static void stopPostgres() {
        try {
            KAFKA.stop();
            POSTGRES.stop();
        } finally {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    private ApplicationContextRunner application() {
        String base = "jdbc:postgresql://" + POSTGRES.getHost()
                + ":" + POSTGRES.getMappedPort(5432) + "/";
        String database = "category_" + UUID.randomUUID().toString().replace("-", "");

        new JdbcTemplate(new DriverManagerDataSource(base + "postgres", "postgres", "test"))
                .execute("CREATE DATABASE " + database);

        DataSource dataSource = new DriverManagerDataSource(
                base + database, "postgres", "test");

        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").load().migrate();

        return new ApplicationContextRunner()
                .withBean(DataSource.class, () -> dataSource)
                .withUserConfiguration(TestConfig.class)
                .withConfiguration(AutoConfigurations.of(
                        JpaAuditingAutoConfiguration.class, KafkaAutoConfiguration.class,
                        EventsAutoConfiguration.class, OutboxAutoConfiguration.class))
                .withPropertyValues(
                        "nexus.outbox.relay.enabled=false",
                        "spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                        "spring.kafka.producer.acks=all",
                        "spring.kafka.producer.properties[enable.idempotence]=true",
                        "spring.kafka.producer.properties[max.block.ms]=1000",
                        "spring.kafka.producer.properties[request.timeout.ms]=1000",
                        "spring.kafka.producer.properties[delivery.timeout.ms]=3000");
    }

    private static CategoryRequest request(String slug) {
        CategoryRequest request = new CategoryRequest();
        request.setName("  Outbox category  ");
        request.setSlug(slug);
        return request;
    }

    @Test
    void contractValidatorRejectsMissingParentAndMalformedUuid() {
        String id = UUID.randomUUID().toString();
        JsonNode missingParent = MAPPER.readTree("""
                {"eventType":"CategoryCreated","aggregateId":"%s",
                 "payload":{"categoryId":"%s","name":"Category","slug":"category"}}
                """.formatted(id, id));
        assertThatThrownBy(() -> CategoryEventSchemas.assertValid(missingParent))
                .isInstanceOf(AssertionError.class);
        JsonNode malformedUuid = MAPPER.readTree("""
                {"eventType":"CategoryDeleted","aggregateId":"invalid",
                 "payload":{"categoryId":"invalid"}}
                """);
        assertThatThrownBy(() -> CategoryEventSchemas.assertValid(malformedUuid))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void createStoresTheEnvelopeAndKeepsTheNullParentField() {
        application().run(context -> {
            assertThat(context).hasNotFailed();
            CategoryService service = context.getBean(CategoryService.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

            UUID id = service.create(request("created-" + UUID.randomUUID())).getId();
            String aggregateId = id.toString();

            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM outbox
                    WHERE aggregate_type = 'Category' AND aggregate_id = ?
                      AND event_type = 'CategoryCreated' AND event_version = 1
                      AND topic = 'nexus.catalog.events' AND published_at IS NULL
                      AND payload ->> 'eventId' = id::text
                      AND payload ->> 'aggregateId' = aggregate_id
                      AND payload ->> 'eventType' = event_type
                      AND payload ->> 'traceId' = trace_id
                      AND payload #>> '{payload,categoryId}' = aggregate_id
                      AND payload #>> '{payload,name}' = 'Outbox category'
                      AND jsonb_typeof(payload #> '{payload,parentId}') = 'null'
                    """, Integer.class, aggregateId)).isEqualTo(1);
            assertStoredPayloads(jdbc, id);
        });
    }

    @Test
    void createUpdateAndHardDeleteEachWriteOneEvent() {
        application().run(context -> {
            assertThat(context).hasNotFailed();
            CategoryService service = context.getBean(CategoryService.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

            UUID id = service.create(request("flow-" + UUID.randomUUID())).getId();
            CategoryUpdateRequest patch = new CategoryUpdateRequest();
            patch.setName("Updated category");
            service.update(id, patch);
            service.delete(id);

            assertThat(jdbc.queryForList("""
                    SELECT event_type FROM outbox WHERE aggregate_id = ?
                    """, String.class, id.toString()))
                    .containsExactlyInAnyOrder(
                            "CategoryCreated", "CategoryUpdated", "CategoryDeleted");

            assertThat(jdbc.queryForObject("""
                    SELECT payload #>> '{payload,name}' FROM outbox
                    WHERE aggregate_id = ? AND event_type = 'CategoryUpdated'
                    """, String.class, id.toString())).isEqualTo("Updated category");

            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM outbox
                    WHERE aggregate_id = ? AND event_type = 'CategoryDeleted'
                      AND payload -> 'payload' = jsonb_build_object('categoryId', ?::text)
                    """, Integer.class, id.toString(), id.toString())).isEqualTo(1);

            assertThat(jdbc.queryForObject("SELECT count(*) FROM categories WHERE id = ?",
                    Integer.class, id)).isZero();
            assertStoredPayloads(jdbc, id);
        });
    }

    @Test
    void exceptionAfterBothInsertsRollsBackCategoryAndOutbox() {
        application().run(context -> {
            assertThat(context).hasNotFailed();
            CategoryService service = context.getBean(CategoryService.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            TransactionTemplate transaction = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));
            String slug = "rollback-" + UUID.randomUUID();

            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                UUID id = service.create(request(slug)).getId();

                // Chứng minh cả hai INSERT đã thực sự diễn ra trước khi ném lỗi.
                assertThat(jdbc.queryForObject("SELECT count(*) FROM categories WHERE id = ?",
                        Integer.class, id)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                        Integer.class, id.toString())).isEqualTo(1);

                throw new IllegalStateException("force rollback after both writes");
            })).isInstanceOf(IllegalStateException.class)
                    .hasMessage("force rollback after both writes");

            // Kiểm tra sau khi transaction ngoài đã rollback xong.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM categories WHERE slug = ?",
                    Integer.class, slug)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM outbox
                    WHERE payload #>> '{payload,slug}' = ?
                    """, Integer.class, slug)).isZero();
        });
    }

    @Test
    void childGuardDoesNotWriteADeletedEvent() {
        application().run(context -> {
            assertThat(context).hasNotFailed();
            CategoryService service = context.getBean(CategoryService.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            UUID parentId = service.create(request("parent-" + UUID.randomUUID())).getId();
            CategoryRequest child = request("child-" + UUID.randomUUID());
            child.setParentId(parentId);
            service.create(child);

            assertThatThrownBy(() -> service.delete(parentId)).isInstanceOf(DomainException.class);

            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM outbox
                    WHERE aggregate_id = ? AND event_type = 'CategoryDeleted'
                    """, Integer.class, parentId.toString())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM categories WHERE id = ?",
                    Integer.class, parentId)).isEqualTo(1);
        });
    }

    @Test
    void successfulSoftDeleteAlsoWritesOneDeletedEvent() {
        application().run(context -> {
            assertThat(context).hasNotFailed();
            CategoryService service = context.getBean(CategoryService.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            UUID id = service.create(request("soft-" + UUID.randomUUID())).getId();

            jdbc.update("""
                    INSERT INTO products (category_id, seller_id, name, slug)
                    VALUES (?, ?, 'Fixture product', ?)
                    """, id, UUID.randomUUID(), "product-" + UUID.randomUUID());

            // Cấu hình chỉ áp dụng cho application context riêng của test này.
            context.getBean(CatalogProperties.class).setAllowCategoryDeleteWithProduct(true);
            service.delete(id);

            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM categories WHERE id = ? AND deleted_at IS NOT NULL
                    """, Integer.class, id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM outbox
                    WHERE aggregate_id = ? AND event_type = 'CategoryDeleted'
                    """, Integer.class, id.toString())).isEqualTo(1);
            assertStoredPayloads(jdbc, id);
        });
    }

    @Test
    void productGuardAndDuplicateSlugDoNotWriteEvents() {
        application().run(context -> {
            assertThat(context).hasNotFailed();
            CategoryService service = context.getBean(CategoryService.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            String slug = "guard-" + UUID.randomUUID();
            UUID id = service.create(request(slug)).getId();
            jdbc.update("""
                    INSERT INTO products (category_id, seller_id, name, slug)
                    VALUES (?, ?, 'Fixture product', ?)
                    """, id, UUID.randomUUID(), "product-" + UUID.randomUUID());

            assertThatThrownBy(() -> service.delete(id)).isInstanceOf(DomainException.class);
            assertThatThrownBy(() -> service.create(request(slug))).isInstanceOf(DomainException.class);

            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                    Integer.class, id.toString())).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM categories WHERE id = ? AND deleted_at IS NULL
                    """, Integer.class, id)).isEqualTo(1);
        });
    }

    @Test
    void childCreationAndMoveToRootPreserveParentAndExplicitNull() {
        application().run(context -> {
            assertThat(context).hasNotFailed();
            CategoryService service = context.getBean(CategoryService.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            UUID parentId = service.create(request("parent-" + UUID.randomUUID())).getId();
            CategoryRequest child = request("child-" + UUID.randomUUID());
            child.setParentId(parentId);
            UUID childId = service.create(child).getId();
            assertThat(jdbc.queryForObject("""
                    SELECT payload #>> '{payload,parentId}' FROM outbox
                    WHERE aggregate_id = ? AND event_type = 'CategoryCreated'
                    """, String.class, childId.toString())).isEqualTo(parentId.toString());

            CategoryUpdateRequest patch = new CategoryUpdateRequest();
            patch.setParentId(null);
            service.update(childId, patch);
            assertThat(jdbc.queryForObject("""
                    SELECT jsonb_typeof(payload #> '{payload,parentId}') FROM outbox
                    WHERE aggregate_id = ? AND event_type = 'CategoryUpdated'
                    """, String.class, childId.toString())).isEqualTo("null");
            assertStoredPayloads(jdbc, childId);
        });
    }

    @Test
    void outboxInsertFailureAlsoRollsBackCategory() {
        application().run(context -> {
            assertThat(context).hasNotFailed();
            CategoryService service = context.getBean(CategoryService.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.execute("ALTER TABLE outbox ADD CONSTRAINT reject_test_events CHECK (event_version < 0)");
            String slug = "writer-fails-" + UUID.randomUUID();

            assertThatThrownBy(() -> service.create(request(slug))).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM categories WHERE slug = ?",
                    Integer.class, slug)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isZero();
        });
    }

    @Test
    void stoppedKafkaDoesNotBlockHttpCreateAndRelayPublishesTheThreeSchemasInOrder() {
        application().withPropertyValues(
                "nexus.outbox.relay.enabled=true",
                // Control relay calls explicitly, without a scheduler racing assertions.
                "nexus.outbox.relay.initial-delay=3600000").run(context -> {
            assertThat(context).hasNotFailed();
            CategoryService service = context.getBean(CategoryService.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            OutboxRelay relay = context.getBean(OutboxRelay.class);
            var mvc = MockMvcBuilders.standaloneSetup(new CategoryController(service)).build();
            try (AdminClient admin = AdminClient.create(Map.of(
                    "bootstrap.servers", KAFKA.getBootstrapServers(),
                    "default.api.timeout.ms", 3000,
                    "request.timeout.ms", 1000));
                 KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                         "bootstrap.servers", KAFKA.getBootstrapServers(),
                         "group.id", "category-outbox-" + UUID.randomUUID(),
                         "auto.offset.reset", "earliest",
                         "enable.auto.commit", false), new StringDeserializer(), new StringDeserializer())) {
                admin.createTopics(List.of(new NewTopic(Topics.CATALOG, 3, (short) 1)))
                        .all().get(15, java.util.concurrent.TimeUnit.SECONDS);
                consumer.subscribe(List.of(Topics.CATALOG));

                DockerClientFactory.instance().client().stopContainerCmd(KAFKA.getContainerId()).exec();
                UUID id;
                String originalEnvelope;
                try {
                    String slug = "kafka-down-" + UUID.randomUUID();
                    String response = mvc.perform(post("/api/v1/categories")
                                    .contentType("application/json")
                                    .content("{\"name\":\"Kafka down\",\"slug\":\"" + slug + "\",\"parentId\":null}"))
                            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
                    id = UUID.fromString(MAPPER.readTree(response).path("data").path("id").asString());
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM categories WHERE id = ?",
                            Integer.class, id)).isEqualTo(1);
                    assertThat(pending(jdbc, id)).isEqualTo(1);
                    originalEnvelope = jdbc.queryForObject("SELECT payload::text FROM outbox WHERE aggregate_id = ?",
                            String.class, id.toString());

                    relay.publishPending();
                    assertThat(pending(jdbc, id)).isEqualTo(1);
                } finally {
                    DockerClientFactory.instance().client().startContainerCmd(KAFKA.getContainerId()).exec();
                }

                await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() ->
                        assertThat(admin.describeCluster().nodes().get(2, java.util.concurrent.TimeUnit.SECONDS))
                                .isNotEmpty());
                CategoryUpdateRequest patch = new CategoryUpdateRequest();
                patch.setName("Kafka recovered");
                service.update(id, patch);
                service.delete(id);
                assertThat(pending(jdbc, id)).isEqualTo(3);

                await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                        .untilAsserted(() -> {
                            relay.publishPending();
                            assertThat(pending(jdbc, id)).isZero();
                        });

                List<ConsumerRecord<String, String>> records = new ArrayList<>();
                await().atMost(Duration.ofSeconds(20)).until(() -> {
                    consumer.poll(Duration.ofMillis(250)).forEach(records::add);
                    return records.size() >= 3;
                });
                consumer.poll(Duration.ofSeconds(1)).forEach(records::add);
                assertThat(records).hasSize(3);
                List<JsonNode> envelopes = records.stream().map(record -> MAPPER.readTree(record.value())).toList();
                assertThat(envelopes).extracting(envelope -> envelope.path("eventType").asString())
                        .containsExactly("CategoryCreated", "CategoryUpdated", "CategoryDeleted");
                assertThat(records).extracting(ConsumerRecord::key).containsOnly(id.toString());
                assertThat(records).extracting(ConsumerRecord::partition).containsOnly(records.getFirst().partition());
                assertThat(records.get(0).offset()).isLessThan(records.get(1).offset());
                assertThat(records.get(1).offset()).isLessThan(records.get(2).offset());
                assertThat(envelopes.getFirst()).isEqualTo(MAPPER.readTree(originalEnvelope));
                assertThat(envelopes.get(1).path("payload").path("name").asString()).isEqualTo("Kafka recovered");
                envelopes.forEach(CategoryEventSchemas::assertValid);
                assertStoredPayloads(jdbc, id);
            } finally {
                context.getBean(EventKafka.TEMPLATE, KafkaTemplate.class).getProducerFactory().reset();
            }
        });
    }

    private static int pending(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND published_at IS NULL
                """, Integer.class, id.toString());
    }

    private static void assertStoredPayloads(JdbcTemplate jdbc, UUID id) {
        jdbc.queryForList("SELECT payload::text FROM outbox WHERE aggregate_id = ?", String.class, id.toString())
                .stream().map(MAPPER::readTree).forEach(CategoryEventSchemas::assertValid);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = CategoryRepository.class)
    static class TestConfig {

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.group5.catalogservice");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        EventEnvelopeFactory envelopeFactory() {
            Tracer tracer = mock(Tracer.class);
            Span span = mock(Span.class);
            TraceContext traceContext = mock(TraceContext.class);
            when(tracer.currentSpan()).thenReturn(span);
            when(span.context()).thenReturn(traceContext);
            when(traceContext.traceId()).thenReturn("4bf92f3577b34da6a3ce929d0e0e4736");
            return new EventEnvelopeFactory(tracer, Clock.systemUTC());
        }

        @Bean
        CatalogProperties catalogProperties() {
            return new CatalogProperties();
        }

        @Bean
        CategoryService categoryService(
                CategoryRepository repository,
                CatalogProperties properties,
                OutboxWriter writer,
                EventEnvelopeFactory envelopeFactory) {
            return new CategoryService(repository, properties, new SlugValidation(), writer, envelopeFactory);
        }
    }

    private static int availablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot allocate a Kafka test port", exception);
        }
    }
}
