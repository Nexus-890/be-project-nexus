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
        registry.add("nexus.storage.supabase-url", () -> LIVE ? requiredEnv("SUPABASE_URL")
                : "http://127.0.0.1:" + STORAGE.getAddress().getPort());
        registry.add("nexus.storage.service-key", () -> LIVE ? requiredEnv("SUPABASE_SERVICE_KEY") : "fixture-key");
        registry.add("nexus.storage.bucket", () -> LIVE ? System.getenv().getOrDefault("SUPABASE_BUCKET", "product-images") : "product-images");
    }

    @BeforeEach
    void setup() {
        OBJECTS.clear(); UPLOADS.set(0);
        properties.setMaxFileSize(org.springframework.util.unit.DataSize.ofMegabytes(5));
        productId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO products (id, category_id, seller_id, name, slug, status)
                VALUES (?, '69000000-0000-4000-8000-000000000102', ?, 'Image fixture', ?, 'ACTIVE')
                """, productId, SELLER, "image-test-" + productId);
        when(decoder.decode("seller")).thenReturn(token("seller", SELLER));
        when(decoder.decode("other-seller")).thenReturn(token("other-seller", UUID.randomUUID()));
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterEach
    void cleanup() {
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
                    UPLOADS.incrementAndGet();
                    OBJECTS.put(path.replace("/object/", "/object/public/"), exchange.getRequestBody().readAllBytes());
                } else if (exchange.getRequestMethod().equals("DELETE")) {
                    OBJECTS.remove(path.replace("/object/", "/object/public/"));
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
