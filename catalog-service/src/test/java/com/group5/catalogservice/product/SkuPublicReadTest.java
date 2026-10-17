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
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(skus.findById(sku.getId())).thenReturn(Optional.of(sku));
        when(skus.findAllByProduct_Id(product.getId())).thenReturn(List.of(sku));
    }

    @AfterEach
    void close() { context.close(); }

    @Test
    void anonymousReadsReturnSkuData() throws Exception {
        mvc.perform(get("/api/v1/products/{id}/skus", product.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(sku.getId().toString()))
                .andExpect(jsonPath("$[0].productId").value(product.getId().toString()));
        mvc.perform(get("/api/v1/skus/{id}", sku.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.price").value(150000));
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
        mvc.perform(get("/api/v1/products/{id}/skus", UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/skus/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void visibleProductWithoutSkusReturnsEmptyArray() throws Exception {
        when(skus.findAllByProduct_Id(product.getId())).thenReturn(List.of());
        mvc.perform(get("/api/v1/products/{id}/skus", product.getId()))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
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
        @Bean ProductRepository products() { return mock(ProductRepository.class); }
        @Bean SkuRepository skus() { return mock(SkuRepository.class); }
        @Bean SkuService service(SkuRepository skus, ProductRepository products) {
            return new SkuService(skus, products);
        }
        @Bean SkuController controller(SkuService service) { return new SkuController(service); }
        @Bean GlobalExceptionHandler errors() { return new GlobalExceptionHandler(mock(Tracer.class)); }
    }
}
