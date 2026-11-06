package com.group5.notificationservice.delivery;

public class DeliveryFailure extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    public DeliveryFailure(String errorCode, boolean retryable, Throwable cause) {
        super(errorCode, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public DeliveryFailure(String errorCode, boolean retryable) {
        this(errorCode, retryable, null);
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
