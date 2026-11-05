package com.group5.auctionservice.auction;

import com.group5.auctionservice.auction.dto.AuctionResponse;
import com.group5.auctionservice.auction.dto.CreateAuctionRequest;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auctions")
public class AuctionController {

    private final AuctionService auctions;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SELLER') and hasAuthority('AUCTION.CREATE')")
    public ApiResponse<AuctionResponse> create(@Valid @RequestBody CreateAuctionRequest request, CurrentUser user) {
        return ApiResponse.of(auctions.create(request, user));
    }
}
