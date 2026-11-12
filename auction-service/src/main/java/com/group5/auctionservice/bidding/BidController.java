package com.group5.auctionservice.bidding;

import com.group5.auctionservice.bidding.dto.BidResponse;
import com.group5.auctionservice.bidding.dto.PlaceBidRequest;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auctions/{id}/bids")
public class BidController {

    private final BidService bids;

    @PostMapping
    @PreAuthorize("hasRole('BUYER') and hasAuthority('AUCTION.BID')")
    public ResponseEntity<ApiResponse<BidResponse>> place(@PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody PlaceBidRequest request, CurrentUser user) {
        BidService.PlacementResult result = bids.place(id, key, request, user);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.of(result.response()));
    }
}
