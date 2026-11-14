package com.group5.paymentservice.payment.controller;

import com.group5.common_security.CurrentUser;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import com.group5.paymentservice.payment.dto.response.PaymentView;
import com.group5.paymentservice.payment.enums.PaymentTransactionStatus;
import com.group5.paymentservice.payment.service.PaymentQueryService;
import com.group5.paymentservice.payment.service.PaymentService;
import com.group5.paymentservice.payment.dto.request.PaymentInitRequest;
import com.group5.paymentservice.payment.dto.response.PaymentInitResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@Validated
public class PaymentController {
    private final PaymentService payments;
    private final PaymentQueryService queries;

    public PaymentController(PaymentService payments, PaymentQueryService queries) {
        this.payments = payments;
        this.queries = queries;
    }

    @GetMapping("/api/v1/payments")
    public ApiResponse<List<PaymentView>> list(CurrentUser user,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) PaymentTransactionStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        if (orderId != null) {
            return ApiResponse.list(queries.forOrder(user, orderId));
        }
        var result = queries.list(user, status, page, size);
        return ApiResponse.page(result.getContent(), new PageInfo(
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    @PostMapping("/api/v1/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CHECKOUT.START')")
    public ApiResponse<PaymentInitResponse> initiate(CurrentUser user,
            @Valid @RequestBody PaymentInitRequest request) {
        return ApiResponse.of(payments.initiate(user, request.orderGroupId()));
    }
}
