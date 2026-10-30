package com.group5.catalogservice.shared.validation;

import com.group5.catalogservice.shared.exception.CatalogErrorCode;
import com.group5.common_core.exception.DomainException;
import org.springframework.stereotype.Component;

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
            throw new DomainException(CatalogErrorCode.INVALID_SLUG, "Slug is required");
        }

        String normalizedSlug = slug.trim();

        if (!isValid(normalizedSlug)) {
            throw new DomainException(CatalogErrorCode.INVALID_SLUG);
        }

        return normalizedSlug;
    }

}
