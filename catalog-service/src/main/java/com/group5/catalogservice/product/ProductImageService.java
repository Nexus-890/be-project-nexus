package com.group5.catalogservice.product;

import com.group5.catalogservice.product.dto.response.ImageResponse;
import com.group5.catalogservice.product.entity.ProductImage;
import com.group5.common_core.constant.ErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class ProductImageService {
    private final ProductImageRepository images;
    private final ImageStorageClient storage;
    private final int maxProductImages;

    public ProductImageService(ProductImageRepository images, ImageStorageClient storage,
                               @Value("${nexus.catalog.max-product-images:5}") int maxProductImages) {
        if (maxProductImages < 1 || maxProductImages > Short.MAX_VALUE) {
            throw new IllegalArgumentException("max-product-images must be between 1 and 32767");
        }
        this.images = images;
        this.storage = storage;
        this.maxProductImages = maxProductImages;
    }

    @Transactional
    public ImageResponse upload(UUID productId, MultipartFile file, CurrentUser user) {
        if (user == null) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED);
        }
        if (!user.hasRole("SELLER") || !user.hasPrivilege("PRODUCT.UPDATE")) {
            throw new DomainException(ErrorCode.UNAUTHORIZED);
        }
        // Serialize uploads for this product; counting alone races under concurrent requests.
        var product = images.findProductForUpload(productId)
                .orElseThrow(() -> new DomainException(ErrorCode.PRODUCT_NOT_FOUND));
        if (!product.getSellerId().equals(user.id())) {
            throw new DomainException(ErrorCode.UNAUTHORIZED);
        }
        var existing = images.findAllByProduct_IdOrderBySortOrderAscIdAsc(productId);
        if (existing.size() >= maxProductImages) {
            throw new DomainException(400, "Product image limit reached");
        }
        int nextOrder = existing.stream().mapToInt(ProductImage::getSortOrder).max().orElse(-1) + 1;
        if (nextOrder > Short.MAX_VALUE) {
            throw new DomainException(400, "Product image sort order limit reached");
        }
        String url = storage.upload(file);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    storage.discard(url);
                }
            }
        });
        ProductImage image = new ProductImage();
        image.setProduct(product);
        image.setImageUrl(url);
        image.setSortOrder((short) nextOrder);
        image.setCreatedAt(OffsetDateTime.now());
        ProductImage saved = images.saveAndFlush(image);
        return new ImageResponse(saved.getId(), saved.getImageUrl(), saved.getSortOrder());
    }
}
