package com.group5.paymentservice.payment.controller;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payment.dto.request.RefundRequest;
import com.group5.paymentservice.payment.dto.response.RefundResult;
import com.group5.paymentservice.payment.dto.response.RefundView;
import com.group5.paymentservice.payment.service.RefundService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/refunds")
public class RefundController {
    private final RefundService refunds;

    public RefundController(RefundService refunds) {
        this.refunds = refunds;
    }

    // CHỈ ADMIN/SUPPORT CÓ ORDER.REFUND; GỬI LẠI CÙNG Idempotency-Key + CÙNG BODY -> TRẢ ĐÚNG REFUND CŨ
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('ORDER.REFUND')")
    public ApiResponse<RefundResult> request(CurrentUser user,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RefundRequest request) {
        return ApiResponse.of(refunds.request(user, idempotencyKey, request));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ORDER.REFUND')")
    public ApiResponse<List<RefundView>> list(@RequestParam UUID orderId) {
        return ApiResponse.list(refunds.list(orderId));
    }

    // SUPPORT BẤM SAU KHI ĐÃ CHUYỂN KHOẢN TAY CHO BUYER; BẤM LẠI KHÔNG TẠO THÊM GIAO DỊCH HAY EVENT
    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('ORDER.REFUND')")
    public ApiResponse<RefundView> complete(CurrentUser user, @PathVariable UUID id) {
        return ApiResponse.of(refunds.complete(user, id));
    }
}
