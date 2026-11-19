package com.group5.catalogservice.product;

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

class ProductDeleteTest {
    private final ProductRepository repository = mock(ProductRepository.class);
    private final CatalogProperties properties = new CatalogProperties();
    private final UUID sellerId = UUID.randomUUID();
    private final CurrentUser seller = new CurrentUser(sellerId, Set.of("SELLER"), Set.of("PRODUCT.DELETE"));

    private ProductService service(OrderReferenceChecker checker) {
        return new ProductService(repository, mock(CategoryRepository.class),
                new ProductSlugGenerator(), checker, properties, mock(com.group5.catalogservice.reputation.SellingEligibilityClient.class));
    }

    private Product product() {
        Product p = new Product();
        p.setSellerId(sellerId);
        when(repository.findForDeleteByIdAndDeletedAtIsNull(p.getId())).thenReturn(Optional.of(p));
        return p;
    }

    @ParameterizedTest
    @EnumSource(ProductStatus.class)
    void softDeletePreservesEveryStatus(ProductStatus status) {
        Product p = product();
        p.setStatus(status);
        service(new StubOrderReferenceChecker()).deleteProduct(p.getId(), seller);
        assertThat(p.getDeletedAt()).isNotNull();
        assertThat(p.getStatus()).isEqualTo(status);
        verify(repository).save(p);
        verify(repository, never()).delete(any());
        verify(repository, never()).deleteById(any());
    }

    @Test
    void referencedProductReturns409WithoutMutation() {
        Product p = product();
        assertThat(properties.isAllowProductDeleteWithOrder()).isFalse();
        OrderReferenceChecker checker = id -> true;
        assertThatThrownBy(() -> service(checker).deleteProduct(p.getId(), seller))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(409));
        assertThat(p.getDeletedAt()).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void explicitConfigAllowsSoftDeleteWithOrders() {
        Product p = product();
        properties.setAllowProductDeleteWithOrder(true);
        service(id -> true).deleteProduct(p.getId(), seller);
        assertThat(p.getDeletedAt()).isNotNull();
        verify(repository).save(p);
    }

    @Test
    void otherSellerIsRejectedBeforeCheckingOrders() {
        Product p = product();
        OrderReferenceChecker checker = mock(OrderReferenceChecker.class);
        CurrentUser other = new CurrentUser(UUID.randomUUID(), seller.roles(), seller.privileges());
        assertThatThrownBy(() -> service(checker).deleteProduct(p.getId(), other))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        assertThat(p.getDeletedAt()).isNull();
        verifyNoInteractions(checker);
        verify(repository, never()).save(any());
    }

    @Test
    void missingOrAlreadyDeletedReturns404() {
        UUID id = UUID.randomUUID();
        when(repository.findForDeleteByIdAndDeletedAtIsNull(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service(p -> false).deleteProduct(id, seller))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(404));
        verify(repository, never()).save(any());
    }

    @Test
    void guestReturns401BeforeRepositoryAccess() {
        assertThatThrownBy(() -> service(p -> false).deleteProduct(UUID.randomUUID(), null))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(401));
        verifyNoInteractions(repository);
    }

    @Test
    void missingPrivilegeReturns403() {
        CurrentUser noPrivilege = new CurrentUser(sellerId, Set.of("SELLER"), Set.of());
        assertThatThrownBy(() -> service(p -> false).deleteProduct(UUID.randomUUID(), noPrivilege))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        verifyNoInteractions(repository);
    }

    @Test
    void buyerWithPrivilegeStillReturns403() {
        CurrentUser buyer = new CurrentUser(sellerId, Set.of("BUYER"), seller.privileges());
        assertThatThrownBy(() -> service(p -> false).deleteProduct(UUID.randomUUID(), buyer))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        verifyNoInteractions(repository);
    }
}
