package com.group5.catalogservice.shared.config;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

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

        private String sortBy = "name";

        private String sortDirection = "ASC";

        private boolean sortIgnoreCase = true;
    }
}

