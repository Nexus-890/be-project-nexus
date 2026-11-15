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

import static com.group5.commerceservice.promotion.MegaSaleEvent.*;

@RestController
@RequestMapping("/api/v1/promotions")
public class MegaSaleEventController {
    private static final String MANAGE = "hasRole('ADMIN') and hasAuthority('PROMOTION.EVENT.MANAGE')";
    private static final String REGISTER = "hasRole('SELLER') and hasAuthority('PROMOTION.EVENT.REGISTER')";
    private final MegaSaleEventService service;

    MegaSaleEventController(MegaSaleEventService service) {
        this.service = service;
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGE)
    public ApiResponse<MegaSaleEvent> create(CurrentUser user, @Valid @RequestBody Write request) {
        return ApiResponse.of(service.create(user.id(), request));
    }

    @GetMapping("/events")
    @PreAuthorize(MANAGE + " or " + REGISTER)
    public ApiResponse<List<MegaSaleEvent>> list(CurrentUser user, @RequestParam(required = false) Status status,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "10") int size) {
        return page(service.list(user, status, page, size));
    }

    @GetMapping("/events/{id}")
    @PreAuthorize(MANAGE + " or " + REGISTER)
    public ApiResponse<MegaSaleEvent> find(CurrentUser user, @PathVariable UUID id) {
        return ApiResponse.of(service.find(user, id));
    }

    @PutMapping("/events/{id}")
    @PreAuthorize(MANAGE)
    public ApiResponse<MegaSaleEvent> edit(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody Write request) {
        return ApiResponse.of(service.edit(user.id(), id, request));
    }

    @PatchMapping("/events/{id}/status")
    @PreAuthorize(MANAGE)
    public ApiResponse<MegaSaleEvent> changeStatus(CurrentUser user, @PathVariable UUID id,
                                                   @Valid @RequestBody ChangeStatus request) {
        return ApiResponse.of(service.status(user.id(), id, request.status()));
    }

    @GetMapping("/events/{id}/sellers")
    @PreAuthorize(MANAGE)
    public ApiResponse<List<Seller>> sellers(@PathVariable UUID id, @RequestParam(required = false) SellerStatus status,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "10") int size) {
        return page(service.sellers(id, status, page, size));
    }

    @PatchMapping("/event-sellers/{id}")
    @PreAuthorize(MANAGE)
    public ApiResponse<Seller> review(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody Review request) {
        return ApiResponse.of(service.review(user.id(), id, request));
    }

    @PostMapping("/events/{id}/registrations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(REGISTER)
    public ApiResponse<Seller> register(CurrentUser user, @PathVariable UUID id) {
        return ApiResponse.of(service.register(user.id(), id));
    }

    @DeleteMapping("/events/{id}/registrations/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(REGISTER)
    public void withdraw(CurrentUser user, @PathVariable UUID id) {
        service.withdraw(user.id(), id);
    }

    private static <T> ApiResponse<List<T>> page(Page<T> page) {
        return ApiResponse.page(page.getContent(),
                new PageInfo(page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages()));
    }
}
