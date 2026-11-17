package com.group5.paymentservice.sellerbank.dto.request;

/** Seller được lấy từ người dùng đã xác thực, không nhận ID từ request. */
public record BankAccountRequest(String bankCode, String accountNumber, String accountHolderName,
        Boolean isDefault, Boolean active) {
    @Override public String toString() { return "BankAccountRequest[redacted]"; }
}
