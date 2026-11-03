package com.group5.common_core.exception;

import com.group5.common_core.constant.CommonErrorCode;
import com.group5.common_core.constant.ErrorCode;
import java.util.Map;
import java.util.Objects;

public class DomainException extends RuntimeException {
    private final int status;
    private final String errorCode;
    private final Map<String, Object> properties;
    public DomainException(ErrorCode code) { this(code, code.message()); }
    public DomainException(ErrorCode code, String detail) {
        this(code, detail, Map.of());
    }
    public DomainException(ErrorCode code, String detail, Map<String, ?> properties) {
        super(detail);
        Objects.requireNonNull(code);
        this.status = code.status().value();
        this.errorCode = code.code();
        this.properties = Map.copyOf(properties);
    }
    /** Compatibility for infrastructure callers; business code uses a named enum. */
    public DomainException(int status, String detail) {
        super(detail);
        if (status < 400 || status > 599) throw new IllegalArgumentException("Expected an error HTTP status");
        this.status = status;
        this.errorCode = CommonErrorCode.forStatus(status).code();
        this.properties = Map.of();
    }
    public DomainException(String detail) { this(CommonErrorCode.INTERNAL_ERROR, detail); }
    public Integer getCode() { return status; }
    public String getErrorCode() { return errorCode; }
    public Map<String, Object> getProperties() { return properties; }
}
