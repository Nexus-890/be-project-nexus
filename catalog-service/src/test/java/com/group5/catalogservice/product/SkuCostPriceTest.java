package com.group5.catalogservice.product;

import com.group5.catalogservice.category.Category;
import com.group5.catalogservice.category.CategoryRepository;
import com.group5.catalogservice.product.dto.request.SkuRequest;
import com.group5.catalogservice.product.entity.Product;
import com.group5.catalogservice.product.entity.Skus;
import com.group5.catalogservice.product.enums.ProductStatus;
import com.group5.catalogservice.product.repository.ProductRepository;
import com.group5.catalogservice.product.repository.SkuRepository;
import com.group5.catalogservice.product.service.ProductService;
import com.group5.catalogservice.product.service.SkuService;
import com.group5.catalogservice.shared.config.CatalogProperties;
import com.group5.catalogservice.shared.util.ProductSlugGenerator;
import com.group5.common_security.CurrentUser;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkuCostPriceTest {
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private final SkuRepository skus = mock(SkuRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final SkuService skuService = new SkuService(skus, products);
    private final ProductService productService = new ProductService(products, mock(CategoryRepository.class),
            new ProductSlugGenerator(), id -> false, new CatalogProperties(), mock(com.group5.catalogservice.reputation.SellingEligibilityClient.class));
    private final UUID sellerId = UUID.randomUUID();
    private final CurrentUser seller = new CurrentUser(sellerId, Set.of("SELLER"),
            Set.of("PRODUCT.UPDATE", "PRODUCT.LIST"));

    @AfterAll
    static void closeValidation() { VALIDATORS.close(); }

    @Test
    void sellerCreateStoresCostSeparatelyFromTheSellingPrice() {
        Product product = product();
        when(skus.findProductForSkuCreate(product.getId())).thenReturn(Optional.of(product));
        when(skus.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SkuRequest request = request(new BigDecimal("150000.00"), new BigDecimal("90000.00"));

        var response = skuService.create(product.getId(), request, seller);

        assertThat(response.getPrice()).isEqualByComparingTo("150000.00");
        assertThat(response.getCostPrice()).isEqualByComparingTo("90000.00");
        verify(skus).saveAndFlush(argThat(saved -> saved.getPrice().compareTo(new BigDecimal("150000.00")) == 0
                && saved.getCostPrice().compareTo(new BigDecimal("90000.00")) == 0));
    }

    @Test
    void updateWithoutCostKeepsTheStoredCost() {
        Skus sku = existingSku(new BigDecimal("90000.00"));

        var response = skuService.update(sku.getId(), request(new BigDecimal("160000.00"), null), seller);

        assertThat(response.getPrice()).isEqualByComparingTo("160000.00");
        assertThat(response.getCostPrice()).isEqualByComparingTo("90000.00");
        assertThat(sku.getCostPrice()).isEqualByComparingTo("90000.00");
    }

    @Test
    void publicSkuReadsNeverExposeTheCost() {
        Skus sku = existingSku(new BigDecimal("90000.00"));
        when(skus.findAllByProduct_Id(sku.getProduct().getId())).thenReturn(List.of(sku));

        var single = skuService.getById(sku.getId());
        var list = skuService.getByProductId(sku.getProduct().getId());

        assertThat(single.getCostPrice()).isNull();
        assertThat(list).singleElement().satisfies(item -> assertThat(item.getCostPrice()).isNull());
        assertThat(JsonMapper.shared().writeValueAsString(single)).doesNotContain("costPrice");
    }

    @Test
    void ownerEditorSeesTheCostButThePublicProductPageDoesNot() {
        Skus sku = existingSku(new BigDecimal("90000.00"));
        Product product = sku.getProduct();
        product.setCategory(new Category());
        product.getSkuses().add(sku);
        when(products.findByIdAndStatusAndDeletedAtIsNull(product.getId(), ProductStatus.ACTIVE))
                .thenReturn(Optional.of(product));

        var owner = productService.getMyProduct(product.getId(), seller);
        var buyer = productService.getById(product.getId());

        assertThat(owner.getSkus()).singleElement()
                .satisfies(item -> assertThat(item.getCostPrice()).isEqualByComparingTo("90000.00"));
        assertThat(buyer.getSkus()).singleElement().satisfies(item -> assertThat(item.getCostPrice()).isNull());
        assertThat(buyer.getMinPrice()).isEqualByComparingTo("150000.00");
        assertThat(JsonMapper.shared().writeValueAsString(buyer)).doesNotContain("costPrice");
    }

    @Test
    void costMayBeZeroOrOmittedButNotNegativeOrOverPrecise() {
        var validator = VALIDATORS.getValidator();
        assertThat(validator.validate(request(BigDecimal.TEN, null))).isEmpty();
        assertThat(validator.validate(request(BigDecimal.TEN, BigDecimal.ZERO))).isEmpty();
        assertThat(validator.validate(request(BigDecimal.TEN, new BigDecimal("-1"))))
                .extracting(violation -> violation.getPropertyPath().toString()).containsExactly("costPrice");
        assertThat(validator.validate(request(BigDecimal.TEN, new BigDecimal("1.001"))))
                .extracting(violation -> violation.getPropertyPath().toString()).containsExactly("costPrice");
    }

    private Product product() {
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setSellerId(sellerId);
        product.setStatus(ProductStatus.ACTIVE);
        return product;
    }

    private Skus existingSku(BigDecimal cost) {
        Skus sku = new Skus();
        sku.setId(UUID.randomUUID());
        sku.setProduct(product());
        sku.setPrice(new BigDecimal("150000.00"));
        sku.setCostPrice(cost);
        when(skus.findById(sku.getId())).thenReturn(Optional.of(sku));
        when(products.findByIdAndDeletedAtIsNull(sku.getProduct().getId())).thenReturn(Optional.of(sku.getProduct()));
        when(products.findById(sku.getProduct().getId())).thenReturn(Optional.of(sku.getProduct()));
        when(skus.saveAndFlush(sku)).thenReturn(sku);
        return sku;
    }

    private SkuRequest request(BigDecimal price, BigDecimal cost) {
        SkuRequest request = new SkuRequest();
        request.setColor("Black");
        request.setSize("M");
        request.setPrice(price);
        request.setCostPrice(cost);
        return request;
    }
}
