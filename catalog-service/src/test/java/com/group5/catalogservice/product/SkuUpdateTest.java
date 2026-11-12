package com.group5.catalogservice.product;

import com.group5.catalogservice.product.dto.request.SkuRequest;
import com.group5.catalogservice.product.entity.Product;
import com.group5.catalogservice.product.entity.Skus;
import com.group5.catalogservice.product.enums.ProductStatus;
import com.group5.catalogservice.product.repository.ProductRepository;
import com.group5.catalogservice.product.repository.SkuRepository;
import com.group5.catalogservice.product.service.SkuService;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkuUpdateTest {
    private final SkuRepository skus = mock(SkuRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final SkuService service = new SkuService(skus, products);
    private final UUID sellerId = UUID.randomUUID();
    private final CurrentUser seller = user(sellerId, "SELLER");

    private CurrentUser user(UUID id, String role) {
        return new CurrentUser(id, Set.of(role), Set.of("PRODUCT.UPDATE"));
    }

    private Skus sku(ProductStatus status) {
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setSellerId(sellerId);
        product.setStatus(status);
        Skus sku = new Skus();
        sku.setId(UUID.randomUUID());
        sku.setProduct(product);
        sku.setSkuCode("OLD");
        sku.setPrice(BigDecimal.TEN);
        when(skus.findById(sku.getId())).thenReturn(Optional.of(sku));
        return sku;
    }

    private SkuRequest request() {
        SkuRequest request = new SkuRequest();
        request.setColor(" Black ");
        request.setSize(" M ");
        request.setPrice(new BigDecimal("150000"));
        return request;
    }

    @ParameterizedTest
    @EnumSource(ProductStatus.class)
    void ownerCanUpdateEveryNonDeletedProductStatusAndTheSkuCodeStays(ProductStatus status) {
        Skus sku = sku(status);
        when(products.findByIdAndDeletedAtIsNull(sku.getProduct().getId()))
                .thenReturn(Optional.of(sku.getProduct()));
        when(skus.saveAndFlush(sku)).thenReturn(sku);

        var response = service.update(sku.getId(), request(), seller);

        assertThat(response.getSkuCode()).isEqualTo("OLD");
        assertThat(response.getColor()).isEqualTo("Black");
        assertThat(response.getSize()).isEqualTo("M");
        assertThat(response.getPrice()).isEqualByComparingTo("150000");
        assertThat(sku.getProduct().getStatus()).isEqualTo(status);
        verify(skus).saveAndFlush(sku);
    }

    @Test
    void deletedProductCannotBeUpdatedByOwnerOrAdmin() {
        Skus sku = sku(ProductStatus.ACTIVE);
        when(products.findByIdAndDeletedAtIsNull(sku.getProduct().getId()))
                .thenReturn(Optional.empty());
        for (CurrentUser caller : new CurrentUser[]{seller, user(UUID.randomUUID(), "ADMIN")}) {
            assertThatThrownBy(() -> service.update(sku.getId(), request(), caller))
                    .isInstanceOfSatisfying(DomainException.class,
                            e -> assertThat(e.getCode()).isEqualTo(404));
        }
        assertThat(sku.getSkuCode()).isEqualTo("OLD");
        assertThat(sku.getPrice()).isEqualByComparingTo(BigDecimal.TEN);
        verify(skus, never()).saveAndFlush(any());
    }

    @Test
    void otherSellerIsRejectedButAdminCanUpdate() {
        Skus sku = sku(ProductStatus.DRAFT);
        when(products.findByIdAndDeletedAtIsNull(sku.getProduct().getId()))
                .thenReturn(Optional.of(sku.getProduct()));
        assertThatThrownBy(() -> service.update(sku.getId(), request(), user(UUID.randomUUID(), "SELLER")))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getCode()).isEqualTo(403));
        assertThat(sku.getSkuCode()).isEqualTo("OLD");
        verify(skus, never()).saveAndFlush(any());

        when(skus.saveAndFlush(sku)).thenReturn(sku);
        service.update(sku.getId(), request(), user(UUID.randomUUID(), "ADMIN"));
        verify(skus).saveAndFlush(sku);
    }
}
