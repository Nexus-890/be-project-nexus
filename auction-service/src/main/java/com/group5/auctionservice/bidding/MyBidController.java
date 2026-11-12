package com.group5.auctionservice.bidding;

import com.group5.auctionservice.bidding.dto.MyBidResponse;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import com.group5.common_security.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/bids")
public class MyBidController {
    private final MyBidQuery bids;

    @GetMapping("/mine")
    @PreAuthorize("hasRole('BUYER') and hasAuthority('AUCTION.VIEW_BID_HISTORY')")
    public ApiResponse<List<MyBidResponse>> mine(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            CurrentUser user) {
        var result = bids.mine(user.id(), page, size);
        return ApiResponse.page(result.getContent(), new PageInfo(
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }
}
