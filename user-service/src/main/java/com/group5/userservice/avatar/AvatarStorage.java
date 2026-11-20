package com.group5.userservice.avatar;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.shared.exception.UserErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

@Component
public class AvatarStorage {
    private static final Logger log = LoggerFactory.getLogger(AvatarStorage.class);
    private final AvatarProperties properties;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public AvatarStorage(AvatarProperties properties) {
        this.properties = properties;
    }

    public String upload(UUID userId, AvatarImage image) {
        String objectName = folder(userId) + UUID.randomUUID() + "." + image.extension();
        int status = send(request(objectUrl(objectName))
                .header("Content-Type", image.contentType())
                .header("x-upsert", "false")
                .POST(HttpRequest.BodyPublishers.ofByteArray(image.content())).build());
        if (status == 413) {
            throw new DomainException(UserErrorCode.USER_AVATAR_TOO_LARGE);
        }
        if (status < 200 || status >= 300) {
            throw new DomainException(UserErrorCode.USER_AVATAR_STORAGE_UNAVAILABLE);
        }
        return publicPrefix() + objectName;
    }

    public void discard(UUID userId, String avatarUrl) {
        if (avatarUrl == null || !avatarUrl.startsWith(publicPrefix() + folder(userId))) {
            return;
        }
        String objectName = avatarUrl.substring(publicPrefix().length());
        if (!objectName.substring(folder(userId).length()).matches("[0-9a-f-]{36}\\.(jpg|png|webp)")) {
            return;
        }
        try {
            int status = send(request(objectUrl(objectName)).DELETE().build());
            if ((status < 200 || status >= 300) && status != 404) {
                log.warn("Could not remove a replaced avatar; storage status={}", status);
            }
        } catch (RuntimeException e) {
            log.warn("Could not remove a replaced avatar");
        }
    }

    private static String folder(UUID userId) {
        return "avatars/" + userId + "/";
    }

    private String baseUrl() {
        return properties.getSupabaseUrl().replaceAll("/+$", "");
    }

    private String publicPrefix() {
        return baseUrl() + "/storage/v1/object/public/" + properties.getBucket() + "/";
    }

    private String objectUrl(String objectName) {
        return baseUrl() + "/storage/v1/object/" + properties.getBucket() + "/" + objectName;
    }

    private HttpRequest.Builder request(String url) {
        if (properties.getSupabaseUrl().isBlank() || properties.getServiceKey().isBlank()) {
            throw new DomainException(UserErrorCode.USER_AVATAR_STORAGE_NOT_CONFIGURED);
        }
        try {
            return HttpRequest.newBuilder(URI.create(url)).timeout(properties.getTimeout())
                    .header("Authorization", "Bearer " + properties.getServiceKey())
                    .header("apikey", properties.getServiceKey());
        } catch (IllegalArgumentException e) {
            throw new DomainException(UserErrorCode.USER_AVATAR_STORAGE_NOT_CONFIGURED);
        }
    }

    private int send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainException(UserErrorCode.USER_AVATAR_STORAGE_UNAVAILABLE);
        } catch (IOException e) {
            throw new DomainException(UserErrorCode.USER_AVATAR_STORAGE_UNAVAILABLE);
        }
    }
}
