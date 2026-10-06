package com.group5.catalogservice.shared.validation;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;

@Component
public class SlugValidation {
    private static final String SLUG_REGEX =
            "^[a-z0-9]+(?:-[a-z0-9]+)*$";



    public boolean isValid(String slug) {

        if (slug == null || slug.isBlank()) {
            return false;
        }

        return slug.matches(SLUG_REGEX);
    }

    public String normalizeAndValidate(String slug) {

        if (slug == null) {
            throw new IllegalArgumentException("Slug is required");
        }

        String normalizedSlug = slug.trim();

        if (!isValid(normalizedSlug)) {
            throw new IllegalArgumentException(
                    "Invalid slug format"
            );
        }

        return normalizedSlug;
    }

}
