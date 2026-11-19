package com.group5.userservice.avatar;

import com.group5.common_core.exception.DomainException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarStorageTest {
    private static final UUID USER = UUID.fromString("64fba061-3a91-4a35-a853-221798344510");
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile int reply = 200;
    private HttpServer server;
    private AvatarProperties properties;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " "
                    + exchange.getRequestHeaders().getFirst("Content-Type") + " "
                    + exchange.getRequestHeaders().getFirst("apikey") + " "
                    + exchange.getRequestBody().readAllBytes().length);
            exchange.sendResponseHeaders(reply, -1);
            exchange.close();
        });
        server.start();
        properties = new AvatarProperties();
        properties.setSupabaseUrl(base() + "/");
        properties.setServiceKey("test-key");
        properties.setBucket("avatars-test");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void uploadsUnderTheUsersFolderAndReturnsThePublicUrl() {
        String url = new AvatarStorage(properties).upload(USER, AvatarImage.detect(PNG).orElseThrow());

        assertThat(url).startsWith(base() + "/storage/v1/object/public/avatars-test/avatars/" + USER + "/")
                .matches(".*/[0-9a-f-]{36}\\.png");
        assertThat(calls).singleElement().satisfies(call -> assertThat(call)
                .startsWith("POST /storage/v1/object/avatars-test/avatars/" + USER + "/")
                .endsWith(".png image/png test-key " + PNG.length));
    }

    @Test
    void storageRefusalsBecomeStableErrors() {
        AvatarImage image = AvatarImage.detect(PNG).orElseThrow();
        reply = 413;
        expect(() -> new AvatarStorage(properties).upload(USER, image), "USER_AVATAR_TOO_LARGE");
        reply = 500;
        expect(() -> new AvatarStorage(properties).upload(USER, image), "USER_AVATAR_STORAGE_UNAVAILABLE");
        properties.setServiceKey("");
        expect(() -> new AvatarStorage(properties).upload(USER, image), "USER_AVATAR_STORAGE_NOT_CONFIGURED");
    }

    @Test
    void discardOnlyDeletesThisUsersOwnUploads() {
        AvatarStorage storage = new AvatarStorage(properties);
        String bucket = base() + "/storage/v1/object/public/avatars-test/";

        storage.discard(USER, null);
        storage.discard(USER, "https://cdn.example.com/avatar.png");
        storage.discard(USER, bucket + "avatars/" + UUID.randomUUID() + "/" + UUID.randomUUID() + ".jpg");
        storage.discard(USER, bucket + "avatars/" + USER + "/../products/x.jpg");
        assertThat(calls).isEmpty();
        reply = 500;
        storage.discard(USER, bucket + "avatars/" + USER + "/" + UUID.randomUUID() + ".jpg");
        assertThat(calls).singleElement().satisfies(call -> assertThat(call)
                .startsWith("DELETE /storage/v1/object/avatars-test/avatars/" + USER + "/"));
    }

    @Test
    void onlyJpegPngAndWebpSignaturesAreAccepted() {
        assertThat(AvatarImage.detect(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}))
                .hasValueSatisfying(image -> assertThat(image.extension()).isEqualTo("jpg"));
        assertThat(AvatarImage.detect("RIFF0000WEBPVP8 ".getBytes(StandardCharsets.US_ASCII)))
                .hasValueSatisfying(image -> assertThat(image.contentType()).isEqualTo("image/webp"));
        assertThat(AvatarImage.detect("GIF89a".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(AvatarImage.detect("<svg></svg>".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(AvatarImage.detect(new byte[]{(byte) 0x89, 'P'})).isEmpty();
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void expect(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
