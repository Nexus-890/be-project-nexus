package com.group5.auctionservice.reputation;

import com.group5.auctionservice.reputation.config.RatingEligibilityFeignConfiguration;
import feign.Response;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

@FeignClient(name = "rating-service", contextId = "auctionRating",
        url = "${nexus.auction.rating-base-url:}", configuration = RatingEligibilityFeignConfiguration.class)
public interface RatingEligibilityHttpClient {
    @GetMapping("/internal/v1/eligibility/{userId}")
    Response check(@PathVariable("userId") UUID userId, @RequestParam("action") EligibilityAction action,
                   @RequestHeader("Authorization") String authorization);
}
