package com.group5.catalogservice.shared.exception;

import com.group5.common_core.constant.ErrorCode;
import org.springframework.http.HttpStatus;

/** Stable codes owned by the catalog service; clients should branch on code, not detail. */
public enum CatalogErrorCode implements ErrorCode {
    SLUG_DUPLICATED("Slug duplicated", HttpStatus.CONFLICT),
    CATEGORY_NOT_FOUND("Category not found", HttpStatus.NOT_FOUND),
    CATEGORY_LEVEL_MAX("Category max level", HttpStatus.BAD_REQUEST),
    INVALID_PARENT("Category cannot be its own parent", HttpStatus.BAD_REQUEST),
    RELATIONSHIP_ERROR("Circular category relationship detected", HttpStatus.CONFLICT),
    CATEGORY_HAS_PRODUCTS("Cannot delete a category that has products", HttpStatus.CONFLICT),
    CATEGORY_HAS_CHILDREN("Cannot delete a category that has child categories", HttpStatus.CONFLICT),
    PRODUCT_NOT_FOUND("Product not found, not ACTIVE or deleted", HttpStatus.NOT_FOUND),
    PRODUCT_HAS_ORDERS("Cannot delete a product that has orders", HttpStatus.CONFLICT),
    INVALID_PRODUCT_STATUS_TRANSITION("Product status transition is not allowed", HttpStatus.BAD_REQUEST),
    SKU_DUPLICATE("Duplicate SKU code or variant", HttpStatus.CONFLICT),
    SKU_NOT_FOUND("SKU not found", HttpStatus.NOT_FOUND),
    UNAUTHENTICATED("Unauthenticated!", HttpStatus.UNAUTHORIZED),
    UNAUTHORIZED("You do not have permission!", HttpStatus.FORBIDDEN),
    DATA_INVALID("Invalid data", HttpStatus.BAD_REQUEST),
    NOT_FOUND("Not found!", HttpStatus.NOT_FOUND),
    INVALID_SLUG("Invalid slug format", HttpStatus.BAD_REQUEST);

    private final String message;
    private final HttpStatus status;

    CatalogErrorCode(String message, HttpStatus status) {
        this.message = message;
        this.status = status;
    }

    @Override
    public String code() { return "CATALOG_" + name(); }

    @Override
    public HttpStatus status() { return status; }

    @Override
    public String message() { return message; }
}
