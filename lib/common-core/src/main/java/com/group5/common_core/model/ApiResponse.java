package com.group5.common_core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Success body; HTTP status carries transport semantics. */
public record ApiResponse<T>(
        @JsonInclude(JsonInclude.Include.ALWAYS) T data,
        @JsonInclude(JsonInclude.Include.NON_NULL) PageInfo page) {
    public static <T> ApiResponse<T> of(T data) { return new ApiResponse<>(data, null); }
    public static <T> ApiResponse<List<T>> list(List<T> data) {
        return page(data, new PageInfo(0, Math.max(1, data.size()), data.size(), data.isEmpty() ? 0 : 1));
    }
    public static <T> ApiResponse<List<T>> page(List<T> data, PageInfo page) {
        return new ApiResponse<>(List.copyOf(data), java.util.Objects.requireNonNull(page));
    }
}
