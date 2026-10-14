package com.group5.catalogservice.shared.util;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Component
public class ProductSlugGenerator {

    private static final int MAX_SLUG_LENGTH = 300;

    public String generate(String name, UUID productId) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(productId, "productId must not be null");

        // Chuyển chữ thường và xử lý riêng chữ đ.
        String slug = name.strip()
                .toLowerCase(Locale.ROOT)
                .replace('đ', 'd');

        // Tách dấu khỏi chữ: "á" → "a" + dấu.
        slug = Normalizer.normalize(slug, Normalizer.Form.NFD);

        // Xóa dấu, thay nhóm ký tự không phải a-z hoặc 0-9 bằng "-".
        slug = slug.replaceAll("\\p{M}+", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");

        // Tên chỉ chứa emoji/ký tự đặc biệt.
        if (slug.isEmpty()) {
            slug = "product";
        }

        // Cho phép nhiều sản phẩm cùng tên có slug khác nhau.
        String suffix = "-" + productId;
        int maxBaseLength = MAX_SLUG_LENGTH - suffix.length();

        if (slug.length() > maxBaseLength) {
            slug = slug.substring(0, maxBaseLength)
                    .replaceAll("-+$", "");
        }

        return slug + suffix;
    }
}