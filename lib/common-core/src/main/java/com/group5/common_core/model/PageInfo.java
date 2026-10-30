package com.group5.common_core.model;

/** Zero-based page and requested size, including for empty pages. */
public record PageInfo(int number, int size, long totalElements, int totalPages) {
    public PageInfo {
        if (number < 0 || size < 1 || totalElements < 0 || totalPages < 0)
            throw new IllegalArgumentException("Page metadata must be non-negative and size must be positive");
    }
}
