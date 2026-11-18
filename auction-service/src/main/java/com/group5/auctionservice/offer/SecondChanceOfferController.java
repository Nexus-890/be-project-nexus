package com.group5.auctionservice.offer;

import com.group5.common_core.model.*;
import com.group5.common_security.CurrentUser;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/auctions")
@RequiredArgsConstructor
public class SecondChanceOfferController {
    private final SecondChanceOfferService offers;

    @GetMapping("/{id}/second-chance-offers")
    @PreAuthorize("hasRole('SELLER') and hasAuthority('AUCTION.LIST')")
    public ApiResponse<SecondChanceOfferService.SellerState> seller(@PathVariable UUID id, CurrentUser user) {
        return ApiResponse.of(offers.seller(id,user));
    }
    @PostMapping("/{id}/second-chance-offers")
    @PreAuthorize("hasRole('SELLER') and hasAuthority('AUCTION.UPDATE')")
    public ApiResponse<SecondChanceOfferService.SellerState> send(@PathVariable UUID id, CurrentUser user) {
        return ApiResponse.of(offers.send(id,user));
    }
    @GetMapping("/offers/me")
    @PreAuthorize("hasRole('BUYER') and hasAuthority('AUCTION.VIEW_BID_HISTORY')")
    public ApiResponse<List<SecondChanceOfferService.Offer>> mine(CurrentUser user,
            @RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="10") @Min(1) @Max(100) int size) {
        var result=offers.mine(user.id(),page,size);
        return ApiResponse.page(result.getContent(),new PageInfo(page,size,result.getTotalElements(),result.getTotalPages()));
    }
    @PostMapping("/offers/{offerId}/accept")
    @PreAuthorize("hasRole('BUYER') and hasAuthority('AUCTION.BID')")
    public ApiResponse<SecondChanceOfferService.Offer> accept(@PathVariable UUID offerId, CurrentUser user) {
        return ApiResponse.of(offers.respond(offerId,user,true));
    }
    @PostMapping("/offers/{offerId}/decline")
    @PreAuthorize("hasRole('BUYER') and hasAuthority('AUCTION.BID')")
    public ApiResponse<SecondChanceOfferService.Offer> decline(@PathVariable UUID offerId, CurrentUser user) {
        return ApiResponse.of(offers.respond(offerId,user,false));
    }
}
