package com.group5.common_core.exception;

import com.group5.common_core.constant.ErrorCode;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

@Getter
@FieldDefaults(level = lombok.AccessLevel.PRIVATE)
public class DomainException extends RuntimeException {

    Integer code;

    public DomainException(int code, String message) {
        super(message);
        this.code = code;
    }

    public DomainException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public DomainException(ErrorCode errorCode, String customMessage) {
        super(customMessage);
        this.code = errorCode.getCode();
    }
    public DomainException(String message) {
        super(message);
    }
}
