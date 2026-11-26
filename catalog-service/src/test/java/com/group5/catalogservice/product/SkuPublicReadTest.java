package com.group5.catalogservice.product;

import com.group5.catalogservice.product.controller.SkuController;
import com.group5.catalogservice.product.entity.Product;
import com.group5.catalogservice.product.entity.Skus;
import com.group5.catalogservice.product.enums.ProductStatus;
import com.group5.catalogservice.product.repository.ProductRepository;
import com.group5.catalogservice.product.repository.SkuRepository;
import com.group5.catalogservice.product.service.SkuService;
import com.group5.common_security.autoconfigure.SecurityAutoConfiguration;
import com.group5.common_web.exception.GlobalExceptionHandler;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SkuPublicReadTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private ProductRepository products;
    private SkuRepository skus;
    private Product product;
    private Skus sku;

    @BeforeEach
    void setUp() throws Exception {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        new YamlPropertySourceLoader().load("catalog", new FileSystemResource(
                "../config-service/src/main/resources/config/catalog-service.yml"))
                .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
        context.register(TestConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        products = context.getBean(ProductRepository.class);
        skus = context.getBean(SkuRepository.class);
        product = new Product();
        product.setId(UUID.randomUUID());
        product.setStatus(ProductStatus.ACTIVE);
        sku = new Skus();
        sku.setId(UUID.randomUUID());
        sku.setProduct(product);
        sku.setSkuCode("SHIRT-M");
        sku.setPrice(new BigDecimal("150000"));
        sku.setShippingWeightGrams(1200);
        sku.setShippingLengthCm(30);
        sku.setShippingWidthCm(20);
        sku.setShippingHeightCm(15);
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(skus.findById(sku.getId())).thenReturn(Optional.of(sku));
        when(skus.findAllByProduct_Id(product.getId())).thenReturn(List.of(sku));
    }

    @AfterEach
    void close() { context.close(); }

    @Test
    void privateCostsRequireCommerceMachineIdentityAndAuthority() throws Exception {
        var decoder = context.getBean(org.springframework.security.oauth2.jwt.JwtDecoder.class);
        for (String token : List.of("buyer", "seller", "other-machine", "no-privilege", "commerce")) {
            var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token).header("alg", "RS256")
                    .subject(UUID.randomUUID().toString()).issuedAt(java.time.Instant.now())
                    .expiresAt(java.time.Instant.now().plusSeconds(300))
                    .claim("roles", List.of(token.equals("seller") ? "SELLER" : "BUYER"))
                    .claim("service_account", List.of("commerce", "other-machine", "no-privilege").contains(token))
                    .claim("azp", token.equals("other-machine") ? "nexus-catalog-service" : "nexus-commerce-service")
                    .claim("privileges", List.of(token.equals("no-privilege") ? "ORDER.CREATE" : "CATALOG.COST.READ")).build();
            when(decoder.decode(token)).thenReturn(jwt);
        }
        String body = "{\"skuIds\":[\"" + sku.getId() + "\"]}";
        mvc.perform(post("/internal/v1/catalog/sku-costs").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        for (String token : List.of("buyer", "seller", "other-machine", "no-privilege"))
            mvc.perform(post("/internal/v1/catalog/sku-costs").header("Authorization", "Bearer " + token)
                    .contentType("application/json").content(body)).andExpect(status().isForbidden());
        product.setSellerId(UUID.randomUUID()); sku.setCostPrice(new BigDecimal("90000"));
        when(skus.findCostsByIdIn(List.of(sku.getId()))).thenReturn(List.of(sku));
        mvc.perform(post("/internal/v1/catalog/sku-costs").header("Authorization", "Bearer commerce")
                .contentType("application/json").content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].unitCostPrice").value(90000));
        mvc.perform(post("/internal/v1/catalog/sku-costs").header("Authorization", "Bearer commerce")
                .contentType("application/json").content("{\"skuIds\":[]}")).andExpect(status().isBadRequest());
    }

    @Test
    void anonymousReadsReturnSkuData() throws Exception {
        mvc.perform(get("/api/v1/products/{id}/skus", product.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(sku.getId().toString()))
                .andExpect(jsonPath("$.data[0].productId").value(product.getId().toString()))
                .andExpect(jsonPath("$.data[0].shippingParcel.weightGrams").value(1200))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        mvc.perform(get("/api/v1/skus/{id}", sku.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.price").value(150000))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.shippingParcel.weightGrams").value(1200))
                .andExpect(jsonPath("$.data.shippingParcel.lengthCm").value(30))
                .andExpect(jsonPath("$.data.shippingParcel.widthCm").value(20))
                .andExpect(jsonPath("$.data.shippingParcel.heightCm").value(15));
    }

    @ParameterizedTest
    @EnumSource(value = ProductStatus.class, names = {"DRAFT", "INACTIVE"})
    void hiddenStatusesReturn404(ProductStatus status) throws Exception {
        product.setStatus(status);
        assertHidden();
    }

    @ParameterizedTest
    @EnumSource(ProductStatus.class)
    void deletedProductsReturn404RegardlessOfStatus(ProductStatus status) throws Exception {
        product.setStatus(status);
        product.setDeletedAt(OffsetDateTime.now());
        assertHidden();
    }

    private void assertHidden() throws Exception {
        mvc.perform(get("/api/v1/products/{id}/skus", product.getId())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/skus/{id}", sku.getId())).andExpect(status().isNotFound());
        verify(skus, never()).findAllByProduct_Id(any());
    }

    @Test
    void missingResourcesReturn404() throws Exception {
        mvc.perform(get("/api/v1/products/{id}/skus", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("CATALOG_PRODUCT_NOT_FOUND"));
        mvc.perform(get("/api/v1/skus/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATALOG_SKU_NOT_FOUND"));
    }

    @Test
    void visibleProductWithoutSkusReturnsEmptyArray() throws Exception {
        when(skus.findAllByProduct_Id(product.getId())).thenReturn(List.of());
        mvc.perform(get("/api/v1/products/{id}/skus", product.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
    }

    @Test
    void writesStillRequireAuthentication() throws Exception {
        mvc.perform(post("/api/v1/products/{id}/skus", product.getId())).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/skus/{id}", sku.getId())).andExpect(status().isUnauthorized());
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import(SecurityAutoConfiguration.class)
    static class TestConfig {
        @Bean @Primary org.springframework.security.oauth2.jwt.JwtDecoder decoder() { return mock(org.springframework.security.oauth2.jwt.JwtDecoder.class); }
        @Bean ProductRepository products() { return mock(ProductRepository.class); }
        @Bean SkuRepository skus() { return mock(SkuRepository.class); }
        @Bean SkuService service(SkuRepository skus, ProductRepository products) {
            return new SkuService(skus, products);
        }
        @Bean SkuController controller(SkuService service) { return new SkuController(service); }
        @Bean GlobalExceptionHandler errors() { return new GlobalExceptionHandler(mock(Tracer.class)); }
    }
}
