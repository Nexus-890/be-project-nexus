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
    REPUTATION_RESTRICTED("Selling is temporarily restricted", HttpStatus.FORBIDDEN),
    REPUTATION_TOO_LOW("Reputation is below the selling minimum", HttpStatus.FORBIDDEN),
    REPUTATION_UNAVAILABLE("Reputation eligibility is temporarily unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    IMAGE_REQUIRED("An image file is required", HttpStatus.BAD_REQUEST),
    IMAGE_NOT_FOUND("Product image not found", HttpStatus.NOT_FOUND),
    IMAGE_ORDER_INVALID("imageIds must contain exactly the product's image IDs without duplicates", HttpStatus.BAD_REQUEST),
    IMAGE_STORAGE_PATH_INVALID("Image URL does not belong to the configured product storage", HttpStatus.CONFLICT),
    IMAGE_LIMIT_REACHED("Product image limit reached", HttpStatus.BAD_REQUEST),
    IMAGE_SORT_LIMIT_REACHED("Product image sort order limit reached", HttpStatus.BAD_REQUEST),
    IMAGE_TOO_LARGE("Image exceeds the maximum file size", HttpStatus.PAYLOAD_TOO_LARGE),
    IMAGE_INVALID_TYPE("Only JPEG, PNG and WebP images are supported", HttpStatus.BAD_REQUEST),
    IMAGE_READ_FAILED("Cannot read the image file", HttpStatus.BAD_REQUEST),
    IMAGE_STORAGE_UNAVAILABLE("Image storage is unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    IMAGE_STORAGE_NOT_CONFIGURED("Image storage is not configured", HttpStatus.SERVICE_UNAVAILABLE),
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
