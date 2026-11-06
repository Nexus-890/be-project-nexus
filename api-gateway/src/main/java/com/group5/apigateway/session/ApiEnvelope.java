package com.group5.apigateway.session;

// body {code, message, data} mà mọi service trả khi thành công (ADR-008)
// gateway chạy WebFlux, không import common-web nên tự giữ một bản của đúng dạng này
public record ApiEnvelope<T>(int code, String message, T data) {

    public static <T> ApiEnvelope<T> ok(T data) {
        return new ApiEnvelope<>(0, "response success", data);
    }
}
