package com.group5.catalogservice.product;

import com.group5.catalogservice.product.entity.Product;
import com.group5.catalogservice.product.entity.ProductImage;
import com.group5.catalogservice.shared.config.StorageProperties;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.common_security.CurrentUserArgumentResolver;
import com.group5.common_web.exception.GlobalExceptionHandler;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProductImageUploadTest {
    private final ProductImageRepository images = mock(ProductImageRepository.class);
    private final org.springframework.jdbc.core.JdbcTemplate jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
    private final StorageProperties storageProperties = new StorageProperties();
    private final Product product = new Product();
    private final UUID sellerId = UUID.randomUUID();
    private final CurrentUser seller = new CurrentUser(sellerId, Set.of("SELLER"), Set.of("PRODUCT.UPDATE"));
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final AtomicInteger uploadAttempts = new AtomicInteger();
    private HttpServer bucket;
    private ProductImageService service;
    private ImageStorageClient storage;
    private MockMvc mvc;
    private int providerStatus = 200;

    @BeforeEach
    void setUp() throws Exception {
        bucket = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bucket.createContext("/storage/v1/object/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = exchange.getRequestBody().readAllBytes();
            int status = 200;
            if (exchange.getRequestMethod().equals("POST")) {
                uploadAttempts.incrementAndGet();
                if (!"Bearer test-service-key".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                        || !"test-service-key".equals(exchange.getRequestHeaders().getFirst("apikey"))) {
                    status = 403;
                } else {
                    status = providerStatus;
                    if (status == 200) {
                        String mime = exchange.getRequestHeaders().getFirst("Content-Type");
                        assertThat(mime).isIn("image/jpeg", "image/png", "image/webp");
                        objects.put(path.replace("/object/", "/object/public/"), body);
                    }
                }
                body = "{\"message\":\"provider detail test-service-key\"}".getBytes(StandardCharsets.UTF_8);
            } else if (exchange.getRequestMethod().equals("DELETE")) {
                objects.remove(path.replace("/object/", "/object/public/"));
                body = new byte[0];
            } else {
                body = objects.get(path);
                if (body == null) { status = 404; body = new byte[0]; }
            }
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        bucket.start();
        storageProperties.setSupabaseUrl("http://127.0.0.1:" + bucket.getAddress().getPort());
        storageProperties.setServiceKey("test-service-key");
        storage = new ImageStorageClient(storageProperties);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(UUID.class))).thenReturn(0);
        when(jdbc.update(anyString(), any(UUID.class), any(UUID.class))).thenReturn(1);
        service = new ProductImageService(new ProductImageUploadTransactions(images, jdbc, 5), storage);
        product.setSellerId(sellerId);
        when(images.findProductForUpload(product.getId())).thenReturn(Optional.of(product));
        when(images.findAllByProduct_IdOrderBySortOrderAscIdAsc(product.getId())).thenReturn(List.of());
        when(images.saveAndFlush(any())).thenAnswer(invocation -> {
            ProductImage image = invocation.getArgument(0);
            image.setId(UUID.randomUUID());
            return image;
        });
        var errors = new GlobalExceptionHandler(mock(Tracer.class));
        mvc = MockMvcBuilders.standaloneSetup(new ProductImageController(service))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new ProductImageController.MultipartErrors(errors), errors).build();
        authenticate(seller);
    }

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        bucket.stop(0);
    }

    @Test
    void jpeg200KbReturns201SavesImageAndPublicUrlServesOriginalBytes() throws Exception {
        byte[] jpeg = Arrays.copyOf(image("jpg"), 200 * 1024);
        String json = mvc.perform(multipart("/api/v1/products/{id}/images", product.getId())
                        .file(new MockMultipartFile("file", "untrusted.exe", "application/octet-stream", jpeg)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.sortOrder").value(0))
                .andReturn().getResponse().getContentAsString();
        var captor = org.mockito.ArgumentCaptor.forClass(ProductImage.class);
        verify(images).saveAndFlush(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getProduct()).isSameAs(product);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(URI.create(saved.getImageUrl()).getPath()).matches(
                "/storage/v1/object/public/product-images/products/" + product.getId() + "/[0-9a-f-]{36}\\.jpg");
        assertThat(json).contains(saved.getImageUrl()).doesNotContain("test-service-key");
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(saved.getImageUrl()))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).containsExactly(jpeg);
        assertThat(objects).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "webp"})
    void detectsAllowedFormatsFromContentDespiteWrongNameAndMime(String format) throws Exception {
        byte[] bytes = format.equals("webp")
                ? Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA")
                : image(format);
        String url = storage.upload(product.getId(),
                new MockMultipartFile("file", "file.txt", "text/plain", bytes));
        assertThat(url).contains("/product-images/products/" + product.getId() + "/");
        assertThat(url).endsWith("." + format);
        assertThat(objects).hasSize(1);
    }

    @Test
    void sixthImageReturns400WithoutTouchingBucket() throws Exception {
        when(images.findAllByProduct_IdOrderBySortOrderAscIdAsc(product.getId()))
                .thenReturn(Collections.nCopies(5, new ProductImage()));
        mvc.perform(multipart("/api/v1/products/{id}/images", product.getId()).file(file("jpg")))
                .andExpect(status().isBadRequest());
        assertNoUpload();
    }

    @Test
    void configurableCountLimitIsAppliedBeforeUpload() {
        service = new ProductImageService(new ProductImageUploadTransactions(images, jdbc, 1), storage);
        when(images.findAllByProduct_IdOrderBySortOrderAscIdAsc(product.getId()))
                .thenReturn(List.of(new ProductImage()));
        assertThatThrownBy(() -> service.upload(product.getId(), null, seller))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(400));
        assertNoUpload();
    }

    @Test
    void exeRenamedJpgReturns400WithoutTouchingBucket() throws Exception {
        mvc.perform(multipart("/api/v1/products/{id}/images", product.getId())
                        .file(new MockMultipartFile("file", "photo.jpg", "image/jpeg",
                                "MZ executable content".getBytes(StandardCharsets.US_ASCII))))
                .andExpect(status().isBadRequest());
        assertNoUpload();
    }

    @Test
    void oversizedFileReturns413BeforeReadingOrUploading() throws Exception {
        storageProperties.setMaxFileSize(DataSize.ofBytes(100));
        mvc.perform(multipart("/api/v1/products/{id}/images", product.getId())
                        .file(new MockMultipartFile("file", new byte[101])))
                .andExpect(status().is(413));
        assertNoUpload();
    }

    @Test
    void multipartParserLimitReturns413UsingSharedErrorContract() throws Exception {
        var failing = mock(ProductImageService.class);
        when(failing.upload(any(), any(), any())).thenThrow(new MaxUploadSizeExceededException(100));
        var errors = new GlobalExceptionHandler(mock(Tracer.class));
        MockMvcBuilders.standaloneSetup(new ProductImageController(failing))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new ProductImageController.MultipartErrors(errors), errors).build()
                .perform(multipart("/api/v1/products/{id}/images", product.getId()).file(file("jpg")))
                .andExpect(status().is(413)).andExpect(jsonPath("$.status").value(413));
    }

    @Test
    void otherSellerReturns403WithoutTouchingBucket() throws Exception {
        authenticate(new CurrentUser(UUID.randomUUID(), seller.roles(), seller.privileges()));
        mvc.perform(multipart("/api/v1/products/{id}/images", product.getId()).file(file("jpg")))
                .andExpect(status().isForbidden());
        verify(images, never()).findAllByProduct_IdOrderBySortOrderAscIdAsc(any());
        assertNoUpload();
    }

    @Test
    void buyerWithUpdatePrivilegeStillReturns403() throws Exception {
        authenticate(new CurrentUser(sellerId, Set.of("BUYER"), seller.privileges()));
        mvc.perform(multipart("/api/v1/products/{id}/images", product.getId()).file(file("jpg")))
                .andExpect(status().isForbidden());
        verify(images, never()).findProductForUpload(any());
        assertNoUpload();
    }

    @Test
    void missingPrivilegeReturns403() throws Exception {
        authenticate(new CurrentUser(sellerId, seller.roles(), Set.of()));
        mvc.perform(multipart("/api/v1/products/{id}/images", product.getId()).file(file("jpg")))
                .andExpect(status().isForbidden());
        assertNoUpload();
    }

    @Test
    void guestReturns401() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(multipart("/api/v1/products/{id}/images", product.getId()).file(file("jpg")))
                .andExpect(status().isUnauthorized());
        assertNoUpload();
    }

    @Test
    void missingProductReturns404() throws Exception {
        mvc.perform(multipart("/api/v1/products/{id}/images", UUID.randomUUID()).file(file("jpg")))
                .andExpect(status().isNotFound());
        assertNoUpload();
    }

    @Test
    void missingAndEmptyFilesReturn400() throws Exception {
        mvc.perform(multipart("/api/v1/products/{id}/images", product.getId()))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/products/{id}/images", product.getId())
                        .file(new MockMultipartFile("file", new byte[0])))
                .andExpect(status().isBadRequest());
        assertNoUpload();
    }

    @Test
    void providerFailureDoesNotSaveRowOrExposeProviderResponse() throws Exception {
        providerStatus = 500;
        mvc.perform(multipart("/api/v1/products/{id}/images", product.getId()).file(file("jpg")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("test-service-key"))));
        verify(images, never()).saveAndFlush(any());
        assertThat(objects).isEmpty();
        verify(jdbc).update(eq("DELETE FROM product_image_uploads WHERE id = ?"), any(UUID.class));
    }

    @Test
    void missingStorageConfigurationReturns503() throws Exception {
        storageProperties.setServiceKey("");
        mvc.perform(multipart("/api/v1/products/{id}/images", product.getId()).file(file("jpg")))
                .andExpect(status().isServiceUnavailable());
        assertNoUpload();
    }

    @Test
    void rollbackRemovesUploadedObject() throws Exception {
        doThrow(new IllegalStateException("database unavailable")).when(images).saveAndFlush(any());
        assertThatThrownBy(() -> service.upload(product.getId(), file("jpg"), seller))
                .isInstanceOf(IllegalStateException.class);
        // Compensation runs after the failed metadata transaction has exited.
        assertThat(objects).isEmpty();
        verify(jdbc).update(eq("DELETE FROM product_image_uploads WHERE id = ?"), any(UUID.class));
    }

    @Test
    void nextSortOrderUsesHighestExistingOrder() throws Exception {
        ProductImage existing = new ProductImage();
        existing.setSortOrder((short) 3);
        when(images.findAllByProduct_IdOrderBySortOrderAscIdAsc(product.getId())).thenReturn(List.of(existing));
        assertThat(service.upload(product.getId(), file("jpg"), seller).sortOrder()).isEqualTo((short) 4);
    }

    private void assertNoUpload() {
        assertThat(uploadAttempts).hasValue(0);
        assertThat(objects).isEmpty();
        verify(images, never()).saveAndFlush(any());
    }

    private void authenticate(CurrentUser user) {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "RS256").subject(user.id().toString())
                .claim("roles", List.copyOf(user.roles())).claim("privileges", List.copyOf(user.privileges())).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private MockMultipartFile file(String format) throws Exception {
        return new MockMultipartFile("file", "photo." + format, "image/" + format, image(format));
    }

    private byte[] image(String format) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, output);
        return output.toByteArray();
    }
}
