package com.group5.catalogservice.product;

import com.group5.catalogservice.category.Category;
import com.group5.catalogservice.category.CategoryRepository;
import com.group5.catalogservice.product.entity.Product;
import com.group5.catalogservice.product.enums.ProductStatus;
import com.group5.catalogservice.product.repository.ProductRepository;
import com.group5.catalogservice.product.service.ProductService;
import com.group5.catalogservice.shared.config.CatalogProperties;
import com.group5.catalogservice.shared.util.ProductSlugGenerator;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductOwnerDetailTest {
    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductService service = new ProductService(products, mock(CategoryRepository.class),
            new ProductSlugGenerator(), id -> false, new CatalogProperties(), mock(com.group5.catalogservice.reputation.SellingEligibilityClient.class));
    private final UUID sellerId = UUID.randomUUID();
    private final CurrentUser seller = new CurrentUser(sellerId, Set.of("SELLER"), Set.of("PRODUCT.LIST"));

    @ParameterizedTest
    @EnumSource(ProductStatus.class)
    void ownerCanReadEveryNonDeletedStatusWithoutChangingPublicVisibility(ProductStatus status) {
        Product product = new Product();
        product.setSellerId(sellerId);
        product.setStatus(status);
        product.setName("Own draft");
        product.setDescription("Retain unsaved-to-public data");
        product.setAuctionEnable(true);
        Category category = new Category();
        product.setCategory(category);
        when(products.findByIdAndDeletedAtIsNull(product.getId())).thenReturn(Optional.of(product));

        var response = service.getMyProduct(product.getId(), seller);
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getDescription()).isEqualTo(product.getDescription());
        assertThat(response.getAuctionEnable()).isTrue();
        assertThat(response.getSkus()).isEmpty();
        verify(products, never()).findByIdAndStatusAndDeletedAtIsNull(any(), any());
        verify(products, never()).save(any());
    }

    @Test
    void anotherSellerCannotDistinguishAPrivateDraftFromAMissingProduct() {
        Product product = new Product();
        product.setSellerId(UUID.randomUUID());
        when(products.findByIdAndDeletedAtIsNull(product.getId())).thenReturn(Optional.of(product));
        assertThatThrownBy(() -> service.getMyProduct(product.getId(), seller))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getCode()).isEqualTo(404));
        verify(products, never()).save(any());
    }

    @Test
    void missingDeletedAndUnauthorizedReadsStayRejected() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.getMyProduct(id, null))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getCode()).isEqualTo(401));
        assertThatThrownBy(() -> service.getMyProduct(id, new CurrentUser(sellerId, Set.of("SELLER"), Set.of())))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getCode()).isEqualTo(403));
        assertThatThrownBy(() -> service.getMyProduct(id, new CurrentUser(sellerId, Set.of("BUYER"), seller.privileges())))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getCode()).isEqualTo(403));
        verifyNoInteractions(products);
        when(products.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getMyProduct(id, seller))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getCode()).isEqualTo(404));
    }
}
