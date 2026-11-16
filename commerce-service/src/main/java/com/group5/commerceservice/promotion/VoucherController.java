package com.group5.commerceservice.promotion;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import com.group5.common_security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.group5.commerceservice.promotion.Voucher.*;
import static com.group5.commerceservice.promotion.VoucherPricing.Source;

@RestController
@RequestMapping("/api/v1/vouchers")
public class VoucherController {
    private static final String MANAGE = "hasAnyRole('ADMIN','SELLER') and hasAuthority('VOUCHER.MANAGE')";
    private static final String SELLER = "hasRole('SELLER') and hasAuthority('VOUCHER.MANAGE')";
    private static final String CLAIM = "hasRole('BUYER') and hasAuthority('VOUCHER.CLAIM')";
    private final VoucherService service;

    VoucherController(VoucherService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGE)
    public ApiResponse<View> create(CurrentUser user, @Valid @RequestBody Write request) {
        return ApiResponse.of(service.create(user, request));
    }

    @GetMapping("/manage")
    @PreAuthorize(MANAGE)
    public ApiResponse<List<View>> manage(CurrentUser user, @RequestParam(required = false) Source sourceType,
                                          @RequestParam(required = false) Status status,
                                          @RequestParam(required = false) UUID eventId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "10") int size) {
        return page(service.manage(user, sourceType, status, eventId, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize(MANAGE)
    public ApiResponse<View> detail(CurrentUser user, @PathVariable UUID id) {
        return ApiResponse.of(service.detail(user, id));
    }

    @PutMapping("/{id}")
    @PreAuthorize(MANAGE)
    public ApiResponse<View> edit(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody Write request) {
        return ApiResponse.of(service.edit(user, id, request));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize(MANAGE)
    public ApiResponse<View> status(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody ChangeStatus request) {
        return ApiResponse.of(service.status(user, id, request.status()));
    }

    @PutMapping("/{id}/products")
    @PreAuthorize(MANAGE)
    public ApiResponse<View> products(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody Products request) {
        return ApiResponse.of(service.products(user, id, request.productIds()));
    }

    @PostMapping("/{id}/sellers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(SELLER)
    public ApiResponse<Participation> join(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody Join request) {
        return ApiResponse.of(service.join(user.id(), id, request.acceptedTermsVersion()));
    }

    @DeleteMapping("/{id}/sellers/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(SELLER)
    public void withdraw(CurrentUser user, @PathVariable UUID id) {
        service.withdraw(user.id(), id);
    }

    @GetMapping("/{id}/usages")
    @PreAuthorize(MANAGE)
    public ApiResponse<List<Usage>> usages(CurrentUser user, @PathVariable UUID id,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "10") int size) {
        return page(service.usages(user, id, page, size));
    }

    @GetMapping
    public ApiResponse<List<PublicView>> publicList(@RequestParam(required = false) UUID productId,
                                                   @RequestParam(required = false) UUID sellerId,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "10") int size) {
        return page(service.publicList(productId, sellerId, page, size));
    }

    @PostMapping("/{id}/claim")
    @PreAuthorize(CLAIM)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WalletEntry> claim(CurrentUser user, @PathVariable UUID id) {
        return ApiResponse.of(service.claim(user.id(), id));
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('BUYER') and hasAuthority('VOUCHER.VIEW')")
    public ApiResponse<List<WalletEntry>> wallet(CurrentUser user, @RequestParam(required = false) WalletStatus status,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "10") int size) {
        return page(service.wallet(user.id(), status, page, size));
    }

    private static <T> ApiResponse<List<T>> page(Page<T> page) {
        return ApiResponse.page(page.getContent(),
                new PageInfo(page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages()));
    }
}
