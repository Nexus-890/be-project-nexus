package com.group5.inventoryservice.reservation;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(args = {
        "--spring.config.location=optional:file:" + ReservationTimeoutRefreshIntegrationTest.CONFIG_FILE,
        "--spring.cloud.config.enabled=false", "--spring.cloud.config.import-check.enabled=false"
}, properties = {
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.admin.auto-create=false",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/jwks", "management.tracing.enabled=false",
        "management.endpoints.web.exposure.include=refresh"
})
class ReservationTimeoutRefreshIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    static final String CONFIG_FILE = "target/reservation-timeout-test/inventory-service.properties";
    private static final Path CONFIG = remoteConfig();
    static { POSTGRES.start(); }

    private static final String PATH = "/api/v1/inventory/reservations";
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private JwtDecoder decoder;
    private MockMvc mvc;
    private UUID warehouse;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void cleanUp() throws IOException {
        Files.deleteIfExists(CONFIG);
    }

    @BeforeEach
    void setup() {
        writeTimeout(30);
        jdbc.update("DELETE FROM inventory_movements");
        jdbc.update("DELETE FROM inventory_reservations");
        jdbc.update("DELETE FROM inventory_balances");
        jdbc.update("INSERT INTO warehouses (code, name) VALUES ('WH_TTL', 'Timeout test') ON CONFLICT (code) DO NOTHING");
        warehouse = jdbc.queryForObject("SELECT id FROM warehouses WHERE code = 'WH_TTL'", UUID.class);
        reset(decoder);
        when(decoder.decode("buyer")).thenReturn(token("buyer", "BUYER"));
        when(decoder.decode("admin")).thenReturn(token("admin", "ADMIN"));
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void changingTheTimeoutFrom30To5AndRefreshingAppliesToNewReservationsOnly() throws Exception {
        UUID sku = stock(10);
        refresh();
        UUID before = reserve(sku);
        Instant beforeAt = expiresAt(before);
        assertThat(beforeAt).isCloseTo(Instant.now().plus(Duration.ofMinutes(30)), within(Duration.ofSeconds(2)));

        writeTimeout(5);
        refresh();
        UUID after = reserve(sku);

        assertThat(expiresAt(after)).isCloseTo(Instant.now().plus(Duration.ofMinutes(5)), within(Duration.ofSeconds(2)));
        assertThat(expiresAt(before)).isEqualTo(beforeAt);
    }

    @Test
    void aThreeItemRequestStoresOneUtcExpiresAt30MinutesAheadOnAllThreeRows() throws Exception {
        refresh();
        String body = "{\"orderGroupId\":\"" + UUID.randomUUID() + "\",\"items\":["
                + item(stock(10)) + "," + item(stock(10)) + "," + item(stock(10)) + "]}";

        String response = mvc.perform(post(PATH).header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        String returned = JsonPath.read(response, "$.data.expiresAt");
        assertThat(returned).endsWith("Z");
        List<OffsetDateTime> stored = jdbc.queryForList("SELECT expires_at FROM inventory_reservations",
                OffsetDateTime.class);
        assertThat(stored).hasSize(3);
        assertThat(stored).extracting(OffsetDateTime::toInstant).containsOnly(OffsetDateTime.parse(returned).toInstant());
        assertThat(OffsetDateTime.parse(returned).toInstant())
                .isCloseTo(Instant.now().plus(Duration.ofMinutes(30)), within(Duration.ofSeconds(2)));
    }

    @Test
    void aTimeoutOfZeroIsRefusedByTheRefreshAndTheLastGoodValueStays() throws Exception {
        UUID sku = stock(10);
        writeTimeout(5);
        refresh();
        assertThat(expiresAt(reserve(sku))).isCloseTo(Instant.now().plus(Duration.ofMinutes(5)),
                within(Duration.ofSeconds(2)));

        writeTimeout(0);
        mvc.perform(post("/actuator/refresh").header("Authorization", "Bearer admin"))
                .andExpect(status().is5xxServerError());
        UUID reservation = reserve(sku);

        assertThat(expiresAt(reservation)).isCloseTo(Instant.now().plus(Duration.ofMinutes(5)),
                within(Duration.ofSeconds(2)));
    }

    @Test
    void theRefreshEndpointNeedsAToken() throws Exception {
        mvc.perform(post("/actuator/refresh")).andExpect(status().isUnauthorized());
    }

    private void refresh() throws Exception {
        mvc.perform(post("/actuator/refresh").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk());
    }

    private UUID reserve(UUID sku) throws Exception {
        String body = "{\"orderGroupId\":\"" + UUID.randomUUID() + "\",\"items\":[{\"orderId\":\""
                + UUID.randomUUID() + "\",\"skuId\":\"" + sku + "\",\"quantity\":1}]}";
        String response = mvc.perform(post(PATH).header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(response, "$.data.reservationIds[0]"));
        assertThat(OffsetDateTime.parse(JsonPath.read(response, "$.data.expiresAt")).toInstant())
                .isEqualTo(expiresAt(id));
        return id;
    }

    private static String item(UUID sku) {
        return "{\"orderId\":\"" + UUID.randomUUID() + "\",\"skuId\":\"" + sku + "\",\"quantity\":1}";
    }

    private Instant expiresAt(UUID reservation) {
        return jdbc.queryForObject("SELECT expires_at FROM inventory_reservations WHERE id = ?",
                OffsetDateTime.class, reservation).toInstant();
    }

    private UUID stock(int available) {
        UUID sku = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_balances (sku_id, warehouse_id, total_quantity, available_quantity)
                VALUES (?, ?, ?, ?)
                """, sku, warehouse, available, available);
        return sku;
    }

    private static Path remoteConfig() {
        try {
            Path file = Path.of(CONFIG_FILE);
            Files.createDirectories(file.getParent());
            writeTimeout(file, 30);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeTimeout(int minutes) {
        writeTimeout(CONFIG, minutes);
    }

    private static void writeTimeout(Path file, int minutes) {
        try {
            Files.writeString(file, "nexus.inventory.default-warehouse-code=WH_TTL\n"
                    + "nexus.inventory.reservation-timeout-minutes=" + minutes + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Jwt token(String value, String role) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of(role)).claim("privileges", List.of()).build();
    }
}
