package com.group5.common_core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Success body; HTTP status carries transport semantics. */
public record ApiResponse<T>(
        @JsonInclude(JsonInclude.Include.ALWAYS) Integer code,
        @JsonInclude(JsonInclude.Include.ALWAYS) String message,
        @JsonInclude(JsonInclude.Include.ALWAYS) T data,
        @JsonInclude(JsonInclude.Include.NON_NULL) PageInfo page) {
    private static final String DEFAULT_MESSAGE = "response success";

    public ApiResponse {
        code = code == null ? 0 : code;
        message = message == null ? DEFAULT_MESSAGE : message;
    }

    public ApiResponse(T data, PageInfo page) {
        this(null, null, data, page);
    }

    public static <T> ApiResponse<T> of(T data) { return of(data, null); }
    public static <T> ApiResponse<T> of(T data, String message) {
        return new ApiResponse<>(null, message, data, null);
    }
    public static <T> ApiResponse<List<T>> list(List<T> data) {
        return list(data, null);
    }
    public static <T> ApiResponse<List<T>> list(List<T> data, String message) {
        return page(data, new PageInfo(0, Math.max(1, data.size()), data.size(), data.isEmpty() ? 0 : 1), message);
    }
    public static <T> ApiResponse<List<T>> page(List<T> data, PageInfo page) {
        return page(data, page, null);
    }
    public static <T> ApiResponse<List<T>> page(List<T> data, PageInfo page, String message) {
        return new ApiResponse<>(null, message, List.copyOf(data), java.util.Objects.requireNonNull(page));
    }
}
