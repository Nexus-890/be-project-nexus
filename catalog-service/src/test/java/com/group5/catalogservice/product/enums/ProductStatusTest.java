package com.group5.catalogservice.product.enums;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductStatusTest {

    @ParameterizedTest(name = "{index}: {0} -> {1}, allowed = {2}")
    @CsvSource({
            "DRAFT,    DRAFT,    false",
            "DRAFT,    ACTIVE,   true",
            "DRAFT,    INACTIVE, false",
            "ACTIVE,   DRAFT,    false",
            "ACTIVE,   ACTIVE,   false",
            "ACTIVE,   INACTIVE, true",
            "INACTIVE, DRAFT,    false",
            "INACTIVE, ACTIVE,   true",
            "INACTIVE, INACTIVE, false"
    })
    void shouldValidateStatusTransition(
            ProductStatus from,
            ProductStatus to,
            boolean expectedAllowed
    ) {
        assertEquals(expectedAllowed, from.canTransitionTo(to));
    }
}