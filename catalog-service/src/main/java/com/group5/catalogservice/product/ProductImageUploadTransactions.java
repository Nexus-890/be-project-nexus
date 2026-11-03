package com.group5.catalogservice.product;

import com.group5.catalogservice.product.dto.response.ImageResponse;
import com.group5.catalogservice.product.entity.Product;
import com.group5.catalogservice.product.entity.ProductImage;
import com.group5.catalogservice.shared.exception.CatalogErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class ProductImageUploadTransactions {
    private final ProductImageRepository images;
    private final JdbcTemplate jdbc;
    private final int maxProductImages;

    public ProductImageUploadTransactions(ProductImageRepository images, JdbcTemplate jdbc,
            @Value("${nexus.catalog.max-product-images:5}") int maxProductImages) {
        if (maxProductImages < 1 || maxProductImages > Short.MAX_VALUE) {
            throw new IllegalArgumentException("max-product-images must be between 1 and 32767");
        }
        this.images = images;
        this.jdbc = jdbc;
        this.maxProductImages = maxProductImages;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID reserve(UUID productId, CurrentUser user) {
        lockOwnedProduct(productId, user);
        jdbc.update("DELETE FROM product_image_uploads WHERE product_id = ? AND expires_at <= clock_timestamp()", productId);
        var existing = images.findAllByProduct_IdOrderBySortOrderAscIdAsc(productId);
        int pending = jdbc.queryForObject("SELECT count(*) FROM product_image_uploads WHERE product_id = ?",
                Integer.class, productId);
        if (existing.size() + pending >= maxProductImages) {
            throw new DomainException(CatalogErrorCode.IMAGE_LIMIT_REACHED);
        }
        nextOrder(existing);
        UUID reservation = UUID.randomUUID();
        jdbc.update("INSERT INTO product_image_uploads (id, product_id) VALUES (?, ?)", reservation, productId);
        return reservation;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImageResponse complete(UUID productId, UUID reservation, String url, CurrentUser user) {
        Product product = lockOwnedProduct(productId, user);
        int claimed = jdbc.update("""
                DELETE FROM product_image_uploads
                WHERE id = ? AND product_id = ? AND expires_at > clock_timestamp()
                """, reservation, productId);
        if (claimed != 1) throw new DomainException(CatalogErrorCode.IMAGE_STORAGE_UNAVAILABLE);
        var existing = images.findAllByProduct_IdOrderBySortOrderAscIdAsc(productId);
        if (existing.size() >= maxProductImages) throw new DomainException(CatalogErrorCode.IMAGE_LIMIT_REACHED);
        ProductImage image = new ProductImage();
        image.setProduct(product);
        image.setImageUrl(url);
        image.setSortOrder(nextOrder(existing));
        image.setCreatedAt(OffsetDateTime.now());
        ProductImage saved = images.saveAndFlush(image);
        return new ImageResponse(saved.getId(), saved.getImageUrl(), saved.getSortOrder());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID reservation) {
        jdbc.update("DELETE FROM product_image_uploads WHERE id = ?", reservation);
    }

    // Called inside the transaction for each image mutation.
    Product lockOwnedProduct(UUID productId, CurrentUser user) {
        if (user == null) throw new DomainException(CatalogErrorCode.UNAUTHENTICATED);
        if (!user.hasRole("SELLER") || !user.hasPrivilege("PRODUCT.UPDATE")) {
            throw new DomainException(CatalogErrorCode.UNAUTHORIZED);
        }
        Product product = images.findProductForUpload(productId)
                .orElseThrow(() -> new DomainException(CatalogErrorCode.PRODUCT_NOT_FOUND));
        if (!product.getSellerId().equals(user.id())) throw new DomainException(CatalogErrorCode.UNAUTHORIZED);
        return product;
    }

    private static short nextOrder(java.util.List<ProductImage> existing) {
        int next = existing.stream().mapToInt(ProductImage::getSortOrder).max().orElse(-1) + 1;
        if (next > Short.MAX_VALUE) throw new DomainException(CatalogErrorCode.IMAGE_SORT_LIMIT_REACHED);
        return (short) next;
    }
}
