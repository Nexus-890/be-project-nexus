package com.group5.catalogservice.shared.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import com.group5.catalogservice.category.CategorySortType;

@ConfigurationProperties(prefix = "nexus.catalog")
@Getter
@Setter
@Validated
public class CatalogProperties {
    @Min(1)
    private short categoryMaxLevel = 3;

    private CategoryTree categoryTree = new CategoryTree();

    @Getter
    @Setter
    public static class CategoryTree {

        private CategorySortType sortType = CategorySortType.NAME_ASC;
    }

    private boolean allowCategoryDeleteWithProduct = false;
    private boolean allowProductDeleteWithOrder = false;

    @Valid
    private OrderReferences orderReferences = new OrderReferences();

    @Getter
    @Setter
    public static class OrderReferences {
        @Pattern(regexp = "http|stub")
        private String mode = "http";

        @NotBlank
        private String baseUrl = "http://localhost:8083";

        @Min(1)
        private int connectTimeoutMillis = 1000;

        @Min(1)
        private int readTimeoutMillis = 3000;
    }
}

