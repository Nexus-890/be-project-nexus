package com.group5.catalogservice.product;

import com.group5.catalogservice.category.Category;
import com.group5.catalogservice.category.CategoryRepository;
import com.group5.catalogservice.product.dto.request.ChangeStatusRequest;
import com.group5.catalogservice.product.entity.Product;
import com.group5.catalogservice.product.enums.ProductStatus;
import com.group5.catalogservice.product.repository.ProductRepository;
import com.group5.catalogservice.product.service.ProductService;
import com.group5.catalogservice.reputation.SellingEligibilityClient;
import com.group5.catalogservice.shared.config.CatalogProperties;
import com.group5.catalogservice.shared.exception.CatalogErrorCode;
import com.group5.catalogservice.shared.util.ProductSlugGenerator;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductPublishingReputationTest {
    private final ProductRepository products = mock(ProductRepository.class);
    private final SellingEligibilityClient eligibility = mock(SellingEligibilityClient.class);
    private final ProductService service = new ProductService(products, mock(CategoryRepository.class),
            new ProductSlugGenerator(), id -> false, new CatalogProperties(), eligibility);
    private final CurrentUser seller = new CurrentUser(UUID.randomUUID(), Set.of("SELLER"), Set.of("PRODUCT.UPDATE"));

    @Test
    void blockedSellerCannotActivateADraftOrAnInactiveListing() {
        for (ProductStatus initial : new ProductStatus[]{ProductStatus.DRAFT, ProductStatus.INACTIVE}) {
            Product product = product(initial);
            doThrow(new DomainException(CatalogErrorCode.REPUTATION_RESTRICTED)).when(eligibility).requireEligible(seller.id());
            assertThatThrownBy(() -> service.changeStatusProduct(product.getId(), new ChangeStatusRequest(ProductStatus.ACTIVE), seller))
                    .isInstanceOf(DomainException.class).extracting("errorCode").isEqualTo("CATALOG_REPUTATION_RESTRICTED");
            assertThat(product.getStatus()).isEqualTo(initial);
        }
        verify(products, never()).save(any());
    }

    @Test
    void unpublishingRemainsAvailableWithoutCheckingReputation() {
        Product product = product(ProductStatus.ACTIVE);
        when(products.save(product)).thenReturn(product);
        service.changeStatusProduct(product.getId(), new ChangeStatusRequest(ProductStatus.INACTIVE), seller);
        assertThat(product.getStatus()).isEqualTo(ProductStatus.INACTIVE);
        verifyNoInteractions(eligibility);
    }

    private Product product(ProductStatus status) {
        Product product = new Product();
        product.setSellerId(seller.id());
        product.setCategory(new Category());
        product.setStatus(status);
        when(products.findByIdAndDeletedAtIsNull(product.getId())).thenReturn(Optional.of(product));
        return product;
    }
}
