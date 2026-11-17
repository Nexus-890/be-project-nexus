package com.group5.auctionservice.bidding;

import com.group5.auctionservice.bidding.dto.BidResponse;
import com.group5.auctionservice.bidding.dto.BidHistoryItem;
import com.group5.auctionservice.bidding.dto.PlaceBidRequest;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import com.group5.common_security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auctions")
public class BidController {

    private final BidService bids;
    private final BidHistoryQuery history;

    @GetMapping("/{id}/bids")
    public ApiResponse<List<BidHistoryItem>> list(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return historyPage(history.list(id, page, size));
    }

    @GetMapping("/manage/{id}/bids")
    @PreAuthorize("hasAnyRole('ADMIN','SUPPORT_STAFF') and hasAuthority('AUCTION.VIEW_BID_HISTORY')")
    public ApiResponse<List<BidHistoryItem>> manage(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return historyPage(history.listManaged(id, page, size));
    }

    @PostMapping("/{id}/bids")
    @PreAuthorize("hasRole('BUYER') and hasAuthority('AUCTION.BID')")
    public ResponseEntity<ApiResponse<BidResponse>> place(@PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody PlaceBidRequest request, CurrentUser user) {
        BidService.PlacementResult result = bids.place(id, key, request, user);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.of(result.response()));
    }

    private static ApiResponse<List<BidHistoryItem>> historyPage(Page<BidHistoryItem> result) {
        return ApiResponse.page(result.getContent(), new PageInfo(
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }
}
