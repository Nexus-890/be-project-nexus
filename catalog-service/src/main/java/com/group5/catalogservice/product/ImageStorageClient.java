package com.group5.catalogservice.product;

import com.group5.catalogservice.shared.config.StorageProperties;
import com.group5.common_core.exception.DomainException;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Component
public class ImageStorageClient {
    private static final Logger log = LoggerFactory.getLogger(ImageStorageClient.class);
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");
    private final StorageProperties properties;
    private final Tika tika = new Tika();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public ImageStorageClient(StorageProperties properties) {
        this.properties = properties;
    }

    public String upload(UUID productId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DomainException(400, "An image file is required");
        }
        long limit = properties.getMaxFileSize().toBytes();
        if (file.getSize() > limit) {
            throw new DomainException(413, "Image exceeds the maximum file size");
        }
        byte[] bytes;
        try (var input = file.getInputStream()) {
            bytes = input.readNBytes((int) limit + 1);
        } catch (IOException e) {
            throw new DomainException(400, "Cannot read the image file");
        }
        if (bytes.length > limit) {
            throw new DomainException(413, "Image exceeds the maximum file size");
        }
        // No filename or caller-supplied Content-Type is passed to detection.
        String mimeType = tika.detect(bytes);
        String extension = EXTENSIONS.get(mimeType);
        if (extension == null) {
            throw new DomainException(400, "Only JPEG, PNG and WebP images are supported");
        }
        String objectName = "products/" + productId + "/" + UUID.randomUUID() + "." + extension;
        HttpRequest request = request(objectUrl(objectName))
                .header("Content-Type", mimeType)
                .header("x-upsert", "false")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        int status = send(request);
        if (status == 413) {
            throw new DomainException(413, "Image exceeds the storage file size limit");
        }
        if (status < 200 || status >= 300) {
            throw new DomainException(503, "Image storage is unavailable");
        }
        return baseUrl() + "/storage/v1/object/public/" + properties.getBucket() + "/" + objectName;
    }

    // Internal compensation only; image deletion endpoints belong to #123.
    public void discard(String imageUrl) {
        String prefix = baseUrl() + "/storage/v1/object/public/" + properties.getBucket() + "/";
        if (!imageUrl.startsWith(prefix)) {
            return;
        }
        try {
            int status = send(request(objectUrl(imageUrl.substring(prefix.length()))).DELETE().build());
            if (status < 200 || status >= 300) {
                log.warn("Could not remove rolled-back product image; storage status={}", status);
            }
        } catch (RuntimeException e) {
            // Never log request headers, response bodies or provider exception details.
            log.warn("Could not remove rolled-back product image");
        }
    }

    private String baseUrl() {
        return properties.getSupabaseUrl().replaceAll("/+$", "");
    }

    private String objectUrl(String objectName) {
        return baseUrl() + "/storage/v1/object/" + properties.getBucket() + "/" + objectName;
    }

    private HttpRequest.Builder request(String url) {
        if (properties.getSupabaseUrl().isBlank() || properties.getServiceKey().isBlank()) {
            throw new DomainException(503, "Image storage is not configured");
        }
        try {
            return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + properties.getServiceKey())
                    .header("apikey", properties.getServiceKey());
        } catch (IllegalArgumentException e) {
            throw new DomainException(503, "Image storage is not configured correctly");
        }
    }

    private int send(HttpRequest request) {
        try {
            // Discard provider responses so their contents cannot leak into error logs.
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainException(503, "Image storage request was interrupted");
        } catch (IOException | IllegalArgumentException e) {
            throw new DomainException(503, "Image storage is unavailable");
        }
    }
}
