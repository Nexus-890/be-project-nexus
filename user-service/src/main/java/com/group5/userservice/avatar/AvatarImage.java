package com.group5.userservice.avatar;

import java.util.Arrays;
import java.util.Optional;

public record AvatarImage(byte[] content, String contentType, String extension) {

    public static Optional<AvatarImage> detect(byte[] content) {
        if (starts(content, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(new AvatarImage(content, "image/jpeg", "jpg"));
        }
        if (starts(content, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(new AvatarImage(content, "image/png", "png"));
        }
        if (content.length >= 12 && starts(content, 'R', 'I', 'F', 'F')
                && Arrays.equals(Arrays.copyOfRange(content, 8, 12), new byte[]{'W', 'E', 'B', 'P'})) {
            return Optional.of(new AvatarImage(content, "image/webp", "webp"));
        }
        return Optional.empty();
    }

    private static boolean starts(byte[] content, int... prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((content[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
