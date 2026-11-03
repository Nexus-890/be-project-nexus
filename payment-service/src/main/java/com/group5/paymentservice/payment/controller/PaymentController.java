package com.group5.paymentservice.payment.controller;

import com.group5.common_security.CurrentUser;
import com.group5.common_core.model.ApiResponse;
import com.group5.paymentservice.payment.service.PaymentService;
import com.group5.paymentservice.payment.dto.request.PaymentInitRequest;
import com.group5.paymentservice.payment.dto.response.PaymentInitResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {
    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping("/api/v1/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CHECKOUT.START')")
    public ApiResponse<PaymentInitResponse> initiate(CurrentUser user,
            @Valid @RequestBody PaymentInitRequest request) {
        return ApiResponse.of(payments.initiate(user, request.orderGroupId()));
    }
}
