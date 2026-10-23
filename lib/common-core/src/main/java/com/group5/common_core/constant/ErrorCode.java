package com.group5.common_core.constant;

import org.springframework.http.HttpStatus;

/** Stable machine-readable identity, independent of HTTP status and human detail. */
public interface ErrorCode {
    String code();
    HttpStatus status();
    String message();
}
