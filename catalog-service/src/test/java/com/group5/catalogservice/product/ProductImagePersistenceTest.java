package com.group5.catalogservice.product;

import com.group5.catalogservice.shared.config.StorageProperties;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/catalog-image-test.properties",
        "spring.cloud.config.enabled=false", "spring.cloud.config.import-check.enabled=false",
        "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "spring.kafka.bootstrap-servers=127.0.0.1:1",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "nexus.outbox.relay.enabled=false", "nexus.catalog.order-references.mode=stub",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/jwks", "management.tracing.enabled=false"
})
class ProductImagePersistenceTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    private static final Map<String, byte[]> OBJECTS = new ConcurrentHashMap<>();
    private static final AtomicInteger UPLOADS = new AtomicInteger();
    private static final AtomicInteger DELETE_STATUS = new AtomicInteger(200);
    private static volatile java.util.concurrent.CountDownLatch uploadEntered;
    private static volatile java.util.concurrent.CountDownLatch uploadRelease;
    private static final HttpServer STORAGE = startStorage();
    private static final boolean LIVE = Boolean.getBoolean("catalog.supabase.live");
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final UUID SELLER = UUID.randomUUID();
    static { POSTGRES.start(); }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private WebApplicationContext context;
    @Autowired private ImageStorageClient storage;
    @Autowired private StorageProperties properties;
    @MockitoBean private JwtDecoder decoder;
    private MockMvc mvc;
    private UUID productId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Flyway uses two connections during startup. Shrink only for the slow-upload test.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "1500");
        registry.add("nexus.storage.supabase-url", () -> LIVE ? requiredEnv("SUPABASE_URL")
                : "http://127.0.0.1:" + STORAGE.getAddress().getPort());
        registry.add("nexus.storage.service-key", () -> LIVE ? requiredEnv("SUPABASE_SERVICE_KEY") : "fixture-key");
        registry.add("nexus.storage.bucket", () -> LIVE ? System.getenv().getOrDefault("SUPABASE_BUCKET", "product-images") : "product-images");
    }

    @BeforeEach
    void setup() {
        OBJECTS.clear(); UPLOADS.set(0); DELETE_STATUS.set(200);
        properties.setMaxFileSize(org.springframework.util.unit.DataSize.ofMegabytes(5));
        productId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO products (id, category_id, seller_id, name, slug, status)
                VALUES (?, '69000000-0000-4000-8000-000000000102', ?, 'Image fixture', ?, 'ACTIVE')
                """, productId, SELLER, "image-test-" + productId);
        when(decoder.decode("seller")).thenReturn(token("seller", SELLER));
        when(decoder.decode("other-seller")).thenReturn(token("other-seller", UUID.randomUUID()));
        when(decoder.decode("no-privilege")).thenReturn(Jwt.withTokenValue("no-privilege").header("alg", "RS256")
                .subject(SELLER.toString()).claim("roles", List.of("SELLER")).claim("privileges", List.of()).build());
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterEach
    void cleanup() {
        DELETE_STATUS.set(200);
        jdbc.queryForList("SELECT image_url FROM product_images WHERE product_id = ?", String.class, productId)
                .forEach(storage::discard);
        jdbc.update("DELETE FROM product_images WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
    }

    @AfterAll
    static void closeStorageFixture() { STORAGE.stop(0); }

    @Test
    void jpeg200KbIsPersistedAndItsPublicUrlRenders() throws Exception {
        byte[] bytes = jpeg();
        MvcResult result = upload(bytes).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sortOrder").value(0))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        var data = JSON.readTree(result.getResponse().getContentAsString()).path("data");
        String url = data.path("imageUrl").asString();
        assertThat(jdbc.queryForObject("SELECT image_url FROM product_images WHERE id = ?",
                String.class, UUID.fromString(data.path("id").asString()))).isEqualTo(url);
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).containsExactly(bytes);
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(response.body()))).isNotNull();
    }

    @Test
    void productListAndDetailReturnImagesInSortOrder() throws Exception {
        upload(jpeg()).andExpect(status().isCreated());
        upload(jpeg()).andExpect(status().isCreated());
        List<String> urls = jdbc.queryForList("SELECT image_url FROM product_images WHERE product_id = ? ORDER BY sort_order, id",
                String.class, productId);
        mvc.perform(get("/api/v1/products/{id}", productId).header("Authorization", "Bearer seller"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.images[0].imageUrl").value(urls.get(0)))
                .andExpect(jsonPath("$.data.images[1].imageUrl").value(urls.get(1)));
        mvc.perform(get("/api/v1/products/mine").header("Authorization", "Bearer seller"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].images[0].imageUrl").value(urls.get(0)))
                .andExpect(jsonPath("$.data[0].images[1].imageUrl").value(urls.get(1)));
    }

    @Test
    void concurrentUploadsCannotExceedFiveImages() throws Exception {
        for (int i = 0; i < 4; i++) upload(jpeg()).andExpect(status().isCreated());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> upload(jpeg()).andReturn().getResponse().getStatus());
            var second = pool.submit(() -> upload(jpeg()).andReturn().getResponse().getStatus());
            assertThat(List.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(20, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 400);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_images WHERE product_id = ?",
                Integer.class, productId)).isEqualTo(5);
        assertThat(UPLOADS).hasValue(5);
    }

    @Test
    void anotherSellerCannotUploadAndNoTokenIsRejected() throws Exception {
        mvc.perform(multipart("/api/v1/products/{id}/images", productId)
                        .file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpeg()))
                        .header("Authorization", "Bearer other-seller"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CATALOG_UNAUTHORIZED"));
        mvc.perform(multipart("/api/v1/products/{id}/images", productId)
                        .file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpeg())))
                .andExpect(status().isUnauthorized());
        assertThat(UPLOADS).hasValue(0);
    }

    @Test
    void deleteRemovesRowAndPublicObject() throws Exception {
        var image = JSON.readTree(upload(jpeg()).andReturn().getResponse().getContentAsString()).path("data");
        String imageId = image.path("id").asString();
        String url = image.path("imageUrl").asString();
        mvc.perform(delete("/api/v1/products/{id}/images/{imageId}", productId, imageId)
                        .header("Authorization", "Bearer seller"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_images WHERE id = ?",
                Integer.class, UUID.fromString(imageId))).isZero();
        var deleted = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (LIVE && Boolean.getBoolean("catalog.supabase.legacyNotFound")) {
            // Explicit provider diagnostic, not evidence that the issue's HTTP 404 criterion passes.
            assertThat(deleted.statusCode()).isEqualTo(400);
            assertThat(JSON.readTree(deleted.body()).path("statusCode").asString()).isEqualTo("404");
        } else {
            assertThat(deleted.statusCode()).isEqualTo(404);
        }
    }

    @Test
    void reorderSwapsBothDatabaseAndProductDetailOrder() throws Exception {
        upload(jpeg()).andExpect(status().isCreated()); upload(jpeg()).andExpect(status().isCreated());
        List<UUID> ids = jdbc.queryForList("SELECT id FROM product_images WHERE product_id = ? ORDER BY sort_order",
                UUID.class, productId);
        mvc.perform(put("/api/v1/products/{id}/images/order", productId).header("Authorization", "Bearer seller")
                        .contentType("application/json").content(JSON.writeValueAsString(Map.of("imageIds", List.of(ids.get(1), ids.get(0))))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(ids.get(1).toString()))
                .andExpect(jsonPath("$.data[0].sortOrder").value(0)).andExpect(jsonPath("$.page.totalElements").value(2));
        mvc.perform(get("/api/v1/products/{id}", productId).header("Authorization", "Bearer seller"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.images[0].id").value(ids.get(1).toString()));
        assertThat(jdbc.queryForList("SELECT id FROM product_images WHERE product_id = ? ORDER BY sort_order",
                UUID.class, productId)).containsExactly(ids.get(1), ids.get(0));
    }

    @Test
    void incompleteDuplicateAndForeignReorderListsChangeNothing() throws Exception {
        upload(jpeg()); upload(jpeg());
        List<UUID> ids = jdbc.queryForList("SELECT id FROM product_images WHERE product_id = ? ORDER BY sort_order",
                UUID.class, productId);
        var before = jdbc.queryForList("SELECT * FROM product_images WHERE product_id = ? ORDER BY id", productId);
        for (List<UUID> invalid : List.of(List.of(ids.get(0)), List.of(ids.get(0), ids.get(0)),
                List.of(ids.get(0), UUID.randomUUID()))) {
            mvc.perform(put("/api/v1/products/{id}/images/order", productId).header("Authorization", "Bearer seller")
                            .contentType("application/json").content(JSON.writeValueAsString(Map.of("imageIds", invalid))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATALOG_IMAGE_ORDER_INVALID"));
            assertThat(jdbc.queryForList("SELECT * FROM product_images WHERE product_id = ? ORDER BY id", productId))
                    .isEqualTo(before);
        }
    }

    @Test
    void otherSellerCannotDeleteAndStorageFailureRollsBackTheRow() throws Exception {
        var image = JSON.readTree(upload(jpeg()).andReturn().getResponse().getContentAsString()).path("data");
        String imageId = image.path("id").asString();
        mvc.perform(delete("/api/v1/products/{id}/images/{imageId}", productId, imageId)
                        .header("Authorization", "Bearer other-seller"))
                .andExpect(status().isForbidden());
        DELETE_STATUS.set(500);
        mvc.perform(delete("/api/v1/products/{id}/images/{imageId}", productId, imageId)
                        .header("Authorization", "Bearer seller"))
                .andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_images WHERE id = ?",
                Integer.class, UUID.fromString(imageId))).isEqualTo(1);
    }

    @Test
    void missingImageAndImageFromAnotherProductAreNotFound() throws Exception {
        for (UUID imageId : List.of(UUID.randomUUID(), UUID.fromString("69000000-0000-4000-8000-000000000301"))) {
            mvc.perform(delete("/api/v1/products/{id}/images/{imageId}", productId, imageId)
                            .header("Authorization", "Bearer seller"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CATALOG_IMAGE_NOT_FOUND"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_images WHERE id = '69000000-0000-4000-8000-000000000301'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void mutationsRequireAuthenticationPrivilegeAndOwnership() throws Exception {
        for (String bearer : List.of("", "Bearer no-privilege", "Bearer other-seller")) {
            int expected = bearer.isEmpty() ? 401 : 403;
            mvc.perform(delete("/api/v1/products/{id}/images/{imageId}", productId, UUID.randomUUID())
                            .header("Authorization", bearer)).andExpect(status().is(expected));
            mvc.perform(put("/api/v1/products/{id}/images/order", productId)
                            .header("Authorization", bearer).contentType("application/json").content("{\"imageIds\":[]}"))
                    .andExpect(status().is(expected));
        }
    }

    @Test
    void malformedOrderBodiesChangeNothingAndEmptyGalleryCanBeReordered() throws Exception {
        for (String body : List.of("{}", "{\"imageIds\":null}", "{\"imageIds\":[null]}", "{\"imageIds\":[\"bad-uuid\"]}")) {
            mvc.perform(put("/api/v1/products/{id}/images/order", productId).header("Authorization", "Bearer seller")
                            .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/v1/products/{id}/images/order", productId).header("Authorization", "Bearer seller")
                        .contentType("application/json").content("{\"imageIds\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void foreignStorageUrlIsNeverDeletedAndDatabaseRowRemains() throws Exception {
        var image = JSON.readTree(upload(jpeg()).andReturn().getResponse().getContentAsString()).path("data");
        UUID imageId = UUID.fromString(image.path("id").asString());
        jdbc.update("UPDATE product_images SET image_url = 'https://example.invalid/foreign.jpg' WHERE id = ?", imageId);
        mvc.perform(delete("/api/v1/products/{id}/images/{imageId}", productId, imageId)
                        .header("Authorization", "Bearer seller"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CATALOG_IMAGE_STORAGE_PATH_INVALID"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_images WHERE id = ?", Integer.class, imageId)).isEqualTo(1);
        assertThat(OBJECTS).hasSize(1);
    }

    @Test
    void migrationSeedsExactlyOneImageForEveryDemoProduct() {
        var counts = jdbc.queryForList("""
                SELECT p.id, count(i.id) AS image_count, min(i.sort_order) AS first_order
                FROM products p LEFT JOIN product_images i ON i.product_id = p.id
                WHERE p.id BETWEEN '69000000-0000-4000-8000-000000000201' AND '69000000-0000-4000-8000-000000000205'
                GROUP BY p.id ORDER BY p.id
                """);
        assertThat(counts).hasSize(5).allSatisfy(row -> {
            assertThat(((Number) row.get("image_count")).intValue()).isEqualTo(1);
            assertThat(((Number) row.get("first_order")).intValue()).isZero();
        });
    }

    @Test
    void slowStorageHoldsNeitherProductLockNorTheOnlyPoolConnection() throws Exception {
        var dataSource = context.getBean(com.zaxxer.hikari.HikariDataSource.class);
        dataSource.setMinimumIdle(0);
        dataSource.setMaximumPoolSize(1);
        dataSource.getHikariPoolMXBean().softEvictConnections();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                dataSource.getHikariPoolMXBean().getTotalConnections() <= 1);
        uploadEntered = new java.util.concurrent.CountDownLatch(1);
        uploadRelease = new java.util.concurrent.CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(() -> upload(jpeg()).andReturn().getResponse().getStatus());
            try {
                assertThat(uploadEntered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM product_image_uploads WHERE product_id = ?",
                        Integer.class, productId)).isEqualTo(1);
                var transaction = new org.springframework.transaction.support.TransactionTemplate(
                        context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
                transaction.executeWithoutResult(status -> {
                    jdbc.execute("SET LOCAL lock_timeout = '500ms'");
                    assertThat(jdbc.queryForList("SELECT id FROM products WHERE id = ? FOR UPDATE", productId)).hasSize(1);
                });
            } finally {
                uploadRelease.countDown();
            }
            assertThat(result.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(201);
        } finally {
            uploadEntered = null;
            uploadRelease = null;
            dataSource.setMaximumPoolSize(2);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_image_uploads WHERE product_id = ?",
                Integer.class, productId)).isZero();
    }

    @Test
    void activeReservationsCountTowardLimitAndExpiredReservationsAreReclaimed() throws Exception {
        for (int i = 0; i < 5; i++) {
            jdbc.update("INSERT INTO product_image_uploads(id, product_id) VALUES (?, ?)", UUID.randomUUID(), productId);
        }
        upload(jpeg()).andExpect(status().isBadRequest());
        assertThat(UPLOADS).hasValue(0);
        jdbc.update("UPDATE product_image_uploads SET expires_at = clock_timestamp() - interval '1 second' WHERE product_id = ?", productId);
        upload(jpeg()).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_image_uploads WHERE product_id = ?",
                Integer.class, productId)).isZero();
    }

    @Test
    void realDatabaseInsertFailureRollsBackAndDiscardsUploadedObject() throws Exception {
        jdbc.execute("ALTER TABLE product_images ADD CONSTRAINT reject_test_upload CHECK (product_id <> '" + productId + "')");
        try {
            var file = new MockMultipartFile("file", "image.jpg", "image/jpeg", jpeg());
            var user = new com.group5.common_security.CurrentUser(SELLER, java.util.Set.of("SELLER"), java.util.Set.of("PRODUCT.UPDATE"));
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    context.getBean(ProductImageService.class).upload(productId, file, user))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM product_images WHERE product_id = ?", Integer.class, productId)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM product_image_uploads WHERE product_id = ?", Integer.class, productId)).isZero();
            assertThat(OBJECTS).isEmpty();
        } finally {
            jdbc.execute("ALTER TABLE product_images DROP CONSTRAINT reject_test_upload");
        }
    }

    private org.springframework.test.web.servlet.ResultActions upload(byte[] bytes) throws Exception {
        return mvc.perform(multipart("/api/v1/products/{id}/images", productId)
                .file(new MockMultipartFile("file", "untrusted.exe", "application/octet-stream", bytes))
                .header("Authorization", "Bearer seller"));
    }

    private static byte[] jpeg() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "jpg", output);
        return Arrays.copyOf(output.toByteArray(), 200 * 1024);
    }

    private static Jwt token(String token, UUID subject) {
        return Jwt.withTokenValue(token).header("alg", "RS256").subject(subject.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of("SELLER")).claim("privileges", List.of("PRODUCT.UPDATE", "PRODUCT.LIST")).build();
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Live storage test requires " + name);
        return value;
    }

    private static HttpServer startStorage() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/storage/v1/object/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                byte[] result = new byte[0];
                int status = 200;
                if (exchange.getRequestMethod().equals("POST")) {
                    var entered = uploadEntered;
                    var release = uploadRelease;
                    if (entered != null && release != null) {
                        entered.countDown();
                        try {
                            if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("Timed out waiting for test release");
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new java.io.IOException(exception);
                        }
                    }
                    UPLOADS.incrementAndGet();
                    OBJECTS.put(path.replace("/object/", "/object/public/"), exchange.getRequestBody().readAllBytes());
                } else if (exchange.getRequestMethod().equals("DELETE")) {
                    status = DELETE_STATUS.get();
                    if (status == 200) OBJECTS.remove(path.replace("/object/", "/object/public/"));
                } else {
                    result = OBJECTS.get(path);
                    if (result == null) { result = new byte[0]; status = 404; }
                }
                exchange.sendResponseHeaders(status, result.length == 0 ? -1 : result.length);
                if (result.length > 0) exchange.getResponseBody().write(result);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
}
