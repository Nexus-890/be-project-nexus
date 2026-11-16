package com.group5.paymentservice.sellerbank;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments/seller-bank-accounts")
@RequiredArgsConstructor
public class BankAccountController {
    private final BankAccountService accounts;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SELLER')")
    public ApiResponse<BankAccountView> create(CurrentUser user, @RequestBody BankAccountRequest body) {
        return ApiResponse.of(accounts.create(user, body));
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('SELLER')")
    public ApiResponse<List<BankAccountView>> list(CurrentUser user, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = accounts.list(user, page, size);
        return ApiResponse.page(result.content(), result.page());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SELLER')")
    public ApiResponse<BankAccountView> update(CurrentUser user, @PathVariable UUID id, @RequestBody BankAccountRequest body) {
        return ApiResponse.of(accounts.update(user, id, body));
    }

    @PostMapping("/{id}/default")
    @PreAuthorize("hasRole('SELLER')")
    public ApiResponse<BankAccountView> setDefault(CurrentUser user, @PathVariable UUID id) {
        return ApiResponse.of(accounts.setDefault(user, id));
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<BankAccountView> verify(CurrentUser user, @PathVariable UUID id, @RequestBody BankVerificationRequest body) {
        return ApiResponse.of(accounts.verify(user, id, body));
    }
}
