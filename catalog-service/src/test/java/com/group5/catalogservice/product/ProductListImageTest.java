package com.group5.catalogservice.product;

import com.group5.catalogservice.category.Category;
import com.group5.catalogservice.category.CategoryRepository;
import com.group5.catalogservice.product.dto.response.ImageResponse;
import com.group5.catalogservice.product.dto.response.ProductResponse;
import com.group5.catalogservice.product.entity.Product;
import com.group5.catalogservice.product.entity.ProductImage;
import com.group5.catalogservice.product.enums.ProductStatus;
import com.group5.catalogservice.product.repository.ProductRepository;
import com.group5.catalogservice.product.service.ProductService;
import com.group5.catalogservice.shared.config.CatalogProperties;
import com.group5.catalogservice.shared.util.ProductSlugGenerator;
import com.group5.common_security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class ProductListImageTest {
    private final ProductRepository repository = mock(ProductRepository.class);
    private final ProductService service = new ProductService(repository, mock(CategoryRepository.class),
            new ProductSlugGenerator(), id -> false, new CatalogProperties());
    private final UUID sellerId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();

    @ParameterizedTest
    @ValueSource(strings = {"public", "category", "mine"})
    void pageImagesMatchProductIdsAndMissingImagesStayNull(String listing) {
        var first = productResponse();
        var second = productResponse();
        var withoutImage = productResponse();
        var pageable = PageRequest.of(0, 3);
        var page = new PageImpl<>(List.of(first, second, withoutImage), pageable, 9);
        when(repository.findProductResponsesByStatus(ProductStatus.ACTIVE, pageable)).thenReturn(page);
        when(repository.findProductResponsesByCategory(categoryId, ProductStatus.ACTIVE, pageable)).thenReturn(page);
        when(repository.findProductResponsesBySeller(sellerId, pageable)).thenReturn(page);
        var ids = List.of(first.getId(), second.getId(), withoutImage.getId());
        // Image query ordering need not match the product page ordering.
        when(repository.findPrimaryImagesByProductIds(ids)).thenReturn(List.of(
                imageProjection(second.getId(), "https://example.com/second.jpg"),
                imageProjection(first.getId(), "https://example.com/first.jpg")));

        Page<ProductResponse> result = switch (listing) {
            case "mine" -> service.getMyProducts(
                    new CurrentUser(sellerId, Set.of("SELLER"), Set.of("PRODUCT.LIST")), 0, 3);
            case "category" -> service.getProductsByCategory(categoryId, 0, 3);
            default -> service.getProductsByCategory(null, 0, 3);
        };

        assertThat(result.getContent()).extracting(ProductResponse::getImageUrl)
                .containsExactly("https://example.com/first.jpg", "https://example.com/second.jpg", null);
        assertThat(result.getTotalElements()).isEqualTo(9);
        assertThat(result.getTotalPages()).isEqualTo(3);
        verify(repository, times(1)).findPrimaryImagesByProductIds(ids);
    }

    @Test
    void emptyPageDoesNotQueryImages() {
        when(repository.findProductResponsesByStatus(ProductStatus.ACTIVE, PageRequest.of(0, 12)))
                .thenReturn(Page.empty());

        assertThat(service.getProductsByCategory(null, 0, 12)).isEmpty();

        verify(repository, never()).findPrimaryImagesByProductIds(anyList());
    }

    @Test
    void detailUsesTheFirstImageBySortOrderThenId() {
        var product = new Product();
        product.setCategory(new Category());
        var first = image("00000000-0000-4000-8000-000000000001", (short) 0, "first.jpg");
        // PostgreSQL UUID ordering is unsigned; Java UUID.compareTo uses signed longs.
        var tie = image("80000000-0000-4000-8000-000000000002", (short) 0, "tie.jpg");
        var last = image("00000000-0000-4000-8000-000000000003", (short) 1, "last.jpg");
        product.setProductImages(new LinkedHashSet<>(List.of(last, tie, first)));
        when(repository.findByIdAndStatusAndDeletedAtIsNull(product.getId(), ProductStatus.ACTIVE))
                .thenReturn(Optional.of(product));

        var result = service.getById(product.getId());

        assertThat(result.getImageUrl()).isEqualTo("first.jpg");
        assertThat(result.getImages()).extracting(ImageResponse::imageUrl)
                .containsExactly("first.jpg", "tie.jpg", "last.jpg");
        verify(repository, never()).findPrimaryImagesByProductIds(anyList());
    }

    private ProductResponse productResponse() {
        return new ProductResponse(UUID.randomUUID(), "Product", "product", ProductStatus.ACTIVE,
                categoryId, sellerId);
    }

    private ProductRepository.PrimaryImage imageProjection(UUID productId, String imageUrl) {
        return new ProductRepository.PrimaryImage() {
            @Override public UUID getProductId() { return productId; }
            @Override public String getImageUrl() { return imageUrl; }
        };
    }

    private ProductImage image(String id, short sortOrder, String url) {
        var image = new ProductImage();
        image.setId(UUID.fromString(id));
        image.setSortOrder(sortOrder);
        image.setImageUrl(url);
        return image;
    }
}
