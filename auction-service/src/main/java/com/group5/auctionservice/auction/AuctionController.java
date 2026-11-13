package com.group5.auctionservice.auction;

import com.group5.auctionservice.auction.dto.AuctionResponse;
import com.group5.auctionservice.auction.dto.CreateAuctionRequest;
import com.group5.auctionservice.auction.dto.UpdateAuctionRequest;
import com.group5.auctionservice.auction.dto.CancelAuctionRequest;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import com.group5.common_security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auctions")
public class AuctionController {

    private final AuctionService auctions;

    @GetMapping("/mine")
    @PreAuthorize("hasRole('SELLER') and hasAuthority('AUCTION.LIST')")
    public ApiResponse<List<AuctionResponse>> mine(
            @RequestParam(required = false) AuctionStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            CurrentUser user) {
        var result = auctions.mine(status, page, size, user);
        return ApiResponse.page(result.getContent(), new PageInfo(
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    @GetMapping("/{id}")
    public ApiResponse<AuctionResponse> get(@PathVariable UUID id) {
        return ApiResponse.of(auctions.get(id));
    }

    @GetMapping
    public ApiResponse<List<AuctionResponse>> list(
            @RequestParam(defaultValue = "ACTIVE") AuctionStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        var result = auctions.list(status, page, size);
        return ApiResponse.page(result.getContent(), new PageInfo(
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SELLER') and hasAuthority('AUCTION.CREATE')")
    public ApiResponse<AuctionResponse> create(@Valid @RequestBody CreateAuctionRequest request, CurrentUser user) {
        return ApiResponse.of(auctions.create(request, user));
    }

    @PostMapping("/{id}/schedule")
    @PreAuthorize("hasRole('SELLER') and hasAuthority('AUCTION.UPDATE')")
    public ApiResponse<AuctionResponse> schedule(@PathVariable UUID id, CurrentUser user) {
        return ApiResponse.of(auctions.schedule(id, user));
    }

    @PutMapping("/{id}")
    @PreAuthorize("(hasRole('SELLER') or hasRole('ADMIN')) and hasAuthority('AUCTION.UPDATE')")
    public ApiResponse<AuctionResponse> update(@PathVariable UUID id,
                                              @Valid @RequestBody UpdateAuctionRequest request, CurrentUser user) {
        return ApiResponse.of(auctions.update(id, request, user));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('SELLER') and hasAuthority('AUCTION.CANCEL')")
    public ApiResponse<AuctionResponse> cancel(@PathVariable UUID id,
                                              @Valid @RequestBody CancelAuctionRequest request, CurrentUser user) {
        return ApiResponse.of(auctions.cancel(id, request, user, false));
    }

    @PostMapping("/{id}/admin-cancel")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('AUCTION.ADMIN_CANCEL')")
    public ApiResponse<AuctionResponse> adminCancel(@PathVariable UUID id,
                                                   @Valid @RequestBody CancelAuctionRequest request, CurrentUser user) {
        return ApiResponse.of(auctions.cancel(id, request, user, true));
    }
}
