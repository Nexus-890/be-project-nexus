package com.group5.catalogservice.product;

import com.group5.catalogservice.product.dto.response.ImageResponse;
import com.group5.common_security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;

@Service
public class ProductImageService {
    private final ProductImageUploadTransactions uploads;
    private final ImageStorageClient storage;

    public ProductImageService(ProductImageUploadTransactions uploads, ImageStorageClient storage) {
        this.uploads = uploads;
        this.storage = storage;
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
}
