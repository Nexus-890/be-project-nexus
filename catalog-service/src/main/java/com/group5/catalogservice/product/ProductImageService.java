package com.group5.catalogservice.product;

import com.group5.catalogservice.product.dto.response.ImageResponse;
import com.group5.catalogservice.product.entity.ProductImage;
import com.group5.catalogservice.shared.exception.CatalogErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProductImageService {
    private final ProductImageUploadTransactions uploads;
    private final ImageStorageClient storage;
    private final ProductImageRepository images;

    public ProductImageService(ProductImageUploadTransactions uploads, ImageStorageClient storage,
                               ProductImageRepository images) {
        this.uploads = uploads;
        this.storage = storage;
        this.images = images;
    }

    // Reject ambient transactions: storage HTTP must never hold a caller's DB connection.
    @Transactional(propagation = Propagation.NEVER)
    public ImageResponse upload(UUID productId, MultipartFile file, CurrentUser user) {
        UUID reservation = uploads.reserve(productId, user);
        String url = null;
        try {
            url = storage.upload(productId, file);
            // Separate proxied bean commits the metadata transaction before returning.
            return uploads.complete(productId, reservation, url, user);
        } catch (RuntimeException failure) {
            if (url != null) storage.discard(url);
            try {
                uploads.release(reservation);
            } catch (RuntimeException cleanupFailure) {
                // Failed releases expire after five minutes.
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @Transactional
    public void delete(UUID productId, UUID imageId, CurrentUser user) {
        uploads.lockOwnedProduct(productId, user);
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
        uploads.lockOwnedProduct(productId, user);
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

    private static ImageResponse response(ProductImage image) {
        return new ImageResponse(image.getId(), image.getImageUrl(), image.getSortOrder());
    }
}
