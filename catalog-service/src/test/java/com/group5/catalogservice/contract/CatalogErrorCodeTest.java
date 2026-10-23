package com.group5.catalogservice.contract;

import com.group5.catalogservice.shared.exception.CatalogErrorCode;
import com.group5.catalogservice.shared.validation.SlugValidation;
import com.group5.common_core.exception.DomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class CatalogErrorCodeTest {
    @Test
    void codesAreUniqueOwnedByCatalogAndPreservedInDomainExceptions() {
        assertThat(CatalogErrorCode.values()).extracting(CatalogErrorCode::code).doesNotHaveDuplicates();
        for (CatalogErrorCode code : CatalogErrorCode.values()) {
            DomainException error = new DomainException(code);
            assertThat(error.getErrorCode()).startsWith("CATALOG_").isEqualTo(code.code());
            assertThat(error.getCode()).isEqualTo(code.status().value());
            assertThat(error.getMessage()).isEqualTo(code.message()).isNotBlank();
        }
    }

    @Test
    void invalidSlugsUseAStableClientErrorRatherThanAnUnexpectedServerError() {
        var validator = new SlugValidation();
        for (String slug : new String[]{null, "Bad Slug", ""}) {
            assertThatThrownBy(() -> validator.normalizeAndValidate(slug))
                    .isInstanceOfSatisfying(DomainException.class, error -> {
                        assertThat(error.getCode()).isEqualTo(400);
                        assertThat(error.getErrorCode()).isEqualTo("CATALOG_INVALID_SLUG");
                    });
        }
        assertThat(validator.normalizeAndValidate("  shirts-men  ")).isEqualTo("shirts-men");
    }
}
