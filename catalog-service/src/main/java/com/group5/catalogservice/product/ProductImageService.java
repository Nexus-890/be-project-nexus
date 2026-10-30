package com.group5.catalogservice.product;

import com.group5.catalogservice.product.dto.response.ImageResponse;
import com.group5.catalogservice.product.entity.ProductImage;
import com.group5.catalogservice.product.entity.Product;
import com.group5.catalogservice.shared.exception.CatalogErrorCode;
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
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

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
        var product = requireOwnedProduct(productId, user);
        var existing = images.findAllByProduct_IdOrderBySortOrderAscIdAsc(productId);
        if (existing.size() >= maxProductImages) {
            throw new DomainException(CatalogErrorCode.IMAGE_LIMIT_REACHED);
        }
        int nextOrder = existing.stream().mapToInt(ProductImage::getSortOrder).max().orElse(-1) + 1;
        if (nextOrder > Short.MAX_VALUE) {
            throw new DomainException(CatalogErrorCode.IMAGE_SORT_LIMIT_REACHED);
        }
        String url = storage.upload(productId, file);
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
        return response(saved);
    }

    @Transactional
    public void delete(UUID productId, UUID imageId, CurrentUser user) {
        requireOwnedProduct(productId, user);
        ProductImage image = images.findById(imageId)
                .filter(candidate -> candidate.getProduct().getId().equals(productId))
                .orElseThrow(() -> new DomainException(CatalogErrorCode.IMAGE_NOT_FOUND));
        images.delete(image);
        images.flush();
        // Storage failure propagates: the database deletion is rolled back.
        storage.delete(productId, image.getImageUrl());
    }

    @Transactional
    public List<ImageResponse> reorder(UUID productId, List<UUID> imageIds, CurrentUser user) {
        requireOwnedProduct(productId, user);
        List<ProductImage> existing = images.findAllByProduct_IdOrderBySortOrderAscIdAsc(productId);
        if (imageIds == null || imageIds.size() > Short.MAX_VALUE || imageIds.stream().anyMatch(Objects::isNull)
                || imageIds.size() != existing.size() || new HashSet<>(imageIds).size() != existing.size()) {
            throw new DomainException(CatalogErrorCode.IMAGE_ORDER_INVALID);
        }
        Map<UUID, ProductImage> byId = existing.stream()
                .collect(Collectors.toMap(ProductImage::getId, Function.identity()));
        if (!byId.keySet().equals(new HashSet<>(imageIds))) {
            throw new DomainException(CatalogErrorCode.IMAGE_ORDER_INVALID);
        }
        for (int i = 0; i < imageIds.size(); i++) {
            byId.get(imageIds.get(i)).setSortOrder((short) i);
        }
        images.flush();
        return imageIds.stream().map(byId::get).map(ProductImageService::response).toList();
    }

    private Product requireOwnedProduct(UUID productId, CurrentUser user) {
        if (user == null) {
            throw new DomainException(CatalogErrorCode.UNAUTHENTICATED);
        }
        if (!user.hasRole("SELLER") || !user.hasPrivilege("PRODUCT.UPDATE")) {
            throw new DomainException(CatalogErrorCode.UNAUTHORIZED);
        }
        // Serialize all image mutations for this product, including upload count checks.
        var product = images.findProductForUpload(productId)
                .orElseThrow(() -> new DomainException(CatalogErrorCode.PRODUCT_NOT_FOUND));
        if (!product.getSellerId().equals(user.id())) {
            throw new DomainException(CatalogErrorCode.UNAUTHORIZED);
        }
        return product;
    }

    private static ImageResponse response(ProductImage image) {
        return new ImageResponse(image.getId(), image.getImageUrl(), image.getSortOrder());
    }
}
