package com.group5.configservice.systemconfig;

import com.group5.common_core.constant.ErrorCode;
import org.springframework.http.HttpStatus;

/** Mã lỗi ổn định của API cấu hình; không trả nội dung file hoặc token ra ngoài. */
public enum ConfigErrorCode implements ErrorCode {
    CONFIG_KEY_UNKNOWN(HttpStatus.BAD_REQUEST, "Unknown system configuration key"),
    CONFIG_VALUE_INVALID(HttpStatus.BAD_REQUEST, "Invalid system configuration value"),
    CONFIG_KEY_EXTERNALLY_MANAGED(HttpStatus.BAD_REQUEST, "This configuration is managed by Keycloak"),
    CONFIG_UPDATE_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "The runtime configuration could not be updated"),
    CONFIG_IDENTITY_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The effective Keycloak configuration could not be read");

    private final HttpStatus status;
    private final String message;

    /** Lưu trạng thái HTTP và thông báo công khai của lỗi. */
    ConfigErrorCode(HttpStatus status, String message) { this.status = status; this.message = message; }
    /** Trả mã máy đọc được cho Problem Details. */
    @Override public String code() { return name(); }
    /** Trả trạng thái HTTP tương ứng. */
    @Override public HttpStatus status() { return status; }
    /** Trả thông báo an toàn cho người gọi. */
    @Override public String message() { return message; }
}
