package com.group5.catalogservice.contract;

import com.group5.catalogservice.category.CategoryController;
import com.group5.catalogservice.category.CategoryService;
import com.group5.catalogservice.category.dto.CategoryResponse;
import com.group5.catalogservice.category.dto.CategoryTreeResponse;
import com.group5.catalogservice.product.controller.ProductController;
import com.group5.catalogservice.product.controller.SkuController;
import com.group5.catalogservice.product.dto.response.ProductDetailResponse;
import com.group5.catalogservice.product.dto.response.ProductResponse;
import com.group5.catalogservice.product.dto.response.SkuResponse;
import com.group5.catalogservice.product.enums.ProductStatus;
import com.group5.catalogservice.product.enums.SkuStatus;
import com.group5.catalogservice.product.service.ProductService;
import com.group5.catalogservice.product.service.SkuService;
import com.group5.catalogservice.shared.exception.CatalogErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.common_web.exception.GlobalExceptionHandler;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Exercises every delivered catalog controller route through real JSON serialization. */
class CatalogResponseContractTest {
    private final UUID id = UUID.fromString("68000000-0000-4000-8000-000000000003");
    private final UUID sellerId = UUID.fromString("68000000-0000-4000-8000-000000000002");
    private final CurrentUser actor = new CurrentUser(sellerId, Set.of("SELLER"), Set.of("PRODUCT.CREATE"));
    private CategoryService categories;
    private ProductService products;
    private SkuService skus;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        categories = mock(CategoryService.class);
        products = mock(ProductService.class);
        skus = mock(SkuService.class);
        mvc = standaloneSetup(new CategoryController(categories), new ProductController(products), new SkuController(skus))
                .setControllerAdvice(new GlobalExceptionHandler(mock(Tracer.class)))
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType() == CurrentUser.class;
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
                        return actor;
                    }
                }).build();
    }

    @Test
    void categoryRoutesUseDataEnvelopeAndDeleteKeepsNoContent() throws Exception {
        CategoryResponse category = new CategoryResponse(id, null, "Shirts", "shirts", (short) 1, "/shirts/");
        when(categories.create(any())).thenReturn(category);
        when(categories.update(eq(id), any())).thenReturn(category);
        when(categories.getById(id)).thenReturn(category);
        when(categories.getCategoryTree()).thenReturn(List.of(
                new CategoryTreeResponse(id, null, "Shirts", "shirts", (short) 1, List.of())));

        assertObject(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Shirts\",\"slug\":\"shirts\"}"), 201);
        assertObject(patch("/api/v1/categories/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Shirts\"}"), 200);
        assertObject(get("/api/v1/categories/{id}", id), 200);
        mvc.perform(get("/api/v1/categories/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(id.toString()))
                .andExpect(jsonPath("$.data[0].children").isArray())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.totalPages").value(1));
        mvc.perform(delete("/api/v1/categories/{id}", id))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
    }

    @Test
    void productRoutesUseDataAndPageMetadata() throws Exception {
        ProductResponse product = new ProductResponse(id, "Shirt", "shirt", ProductStatus.ACTIVE, id, sellerId);
        when(products.create(any(), eq(actor))).thenReturn(product);
        when(products.update(eq(id), any(), eq(actor))).thenReturn(product);
        when(products.changeStatusProduct(eq(id), any(), eq(actor))).thenReturn(product);
        when(products.getById(id)).thenReturn(new ProductDetailResponse(id, "Shirt", "shirt", ProductStatus.ACTIVE,
                id, sellerId, "Description", false, List.of(), List.of()));
        when(products.getMyProduct(id, actor)).thenReturn(new ProductDetailResponse(id, "Shirt", "shirt", ProductStatus.DRAFT,
                id, sellerId, "Draft description", true, List.of(), List.of()));
        var page = new PageImpl<>(List.of(product), PageRequest.of(1, 2), 5);
        when(products.getProductsByCategory(null, 1, 2)).thenReturn(page);
        when(products.getMyProducts(actor, 1, 2)).thenReturn(page);

        String request = "{\"name\":\"Shirt\",\"categoryId\":\"" + id + "\",\"auctionEnable\":false}";
        assertObject(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON).content(request), 201);
        assertObject(put("/api/v1/products/{id}", id).contentType(MediaType.APPLICATION_JSON).content(request), 200);
        assertObject(get("/api/v1/products/{id}", id), 200);
        mvc.perform(get("/api/v1/products/mine/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id.toString()))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.description").value("Draft description"))
                .andExpect(jsonPath("$.data.auctionEnable").value(true))
                .andExpect(jsonPath("$.data.skus").isArray())
                .andExpect(jsonPath("$.data.images").isArray());
        assertObject(patch("/api/v1/products/{id}/status", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\"}"), 200);

        for (String path : List.of("/api/v1/products", "/api/v1/products/mine")) {
            mvc.perform(get(path).param("page", "1").param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(id.toString()))
                    .andExpect(jsonPath("$.page.number").value(1))
                    .andExpect(jsonPath("$.page.size").value(2))
                    .andExpect(jsonPath("$.page.totalElements").value(5))
                    .andExpect(jsonPath("$.page.totalPages").value(3))
                    .andExpect(jsonPath("$.content").doesNotExist());
        }
        mvc.perform(delete("/api/v1/products/{id}", id))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
    }

    @Test
    void skuRoutesUseDataEnvelopeForObjectsAndLists() throws Exception {
        SkuResponse sku = new SkuResponse(id, id, "SHIRT-M", "Black", "M", BigDecimal.TEN, SkuStatus.ACTIVE, null);
        when(skus.create(eq(id), any(), eq(actor))).thenReturn(sku);
        when(skus.update(eq(id), any(), eq(actor))).thenReturn(sku);
        when(skus.getById(id)).thenReturn(sku);
        when(skus.getByProductId(id)).thenReturn(List.of(sku));

        String request = "{\"skuCode\":\"SHIRT-M\",\"color\":\"Black\",\"size\":\"M\",\"price\":10}";
        assertObject(post("/api/v1/products/{id}/skus", id).contentType(MediaType.APPLICATION_JSON).content(request), 201);
        assertObject(put("/api/v1/skus/{id}", id).contentType(MediaType.APPLICATION_JSON).content(request), 200);
        assertObject(get("/api/v1/skus/{id}", id), 200);
        mvc.perform(get("/api/v1/products/{id}/skus", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(id.toString()))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void ownerDetailNotFoundDoesNotRevealWhetherAnotherSellerOwnsTheProduct() throws Exception {
        when(products.getMyProduct(id, actor)).thenThrow(new DomainException(CatalogErrorCode.PRODUCT_NOT_FOUND));
        mvc.perform(get("/api/v1/products/mine/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CATALOG_PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value("/api/v1/products/mine/" + id))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void skuPackageValidationRejectsMissingAndInvalidNestedMeasurements() throws Exception {
        String request = """
                {"skuCode":"SHIRT-M","color":"Black","size":"M","price":10,
                 "shippingParcel":{"weightGrams":0,"lengthCm":201,"widthCm":20}}
                """;
        for (MockHttpServletRequestBuilder operation : List.of(
                post("/api/v1/products/{id}/skus", id), put("/api/v1/skus/{id}", id))) {
            mvc.perform(operation.contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.errors[*].field", hasItems("shippingParcel.weightGrams",
                            "shippingParcel.lengthCm", "shippingParcel.heightCm")));
        }
        verifyNoInteractions(skus);
    }

    @Test
    void validationReportsAllFailedFieldsAndDomainErrorsExposeStableCode() throws Exception {
        mvc.perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItems("name", "slug")))
                .andExpect(jsonPath("$.errors[0].code").isNotEmpty())
                .andExpect(jsonPath("$.errors[0].detail").isNotEmpty());

        when(categories.getById(id)).thenThrow(new DomainException(CatalogErrorCode.CATEGORY_NOT_FOUND));
        mvc.perform(get("/api/v1/categories/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CATALOG_CATEGORY_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Category not found"))
                .andExpect(jsonPath("$.instance").value("/api/v1/categories/" + id));
    }

    private void assertObject(MockHttpServletRequestBuilder request, int status) throws Exception {
        mvc.perform(request).andExpect(status().is(status))
                .andExpect(jsonPath("$.data.id").value(id.toString()))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.page").doesNotExist());
    }
}
