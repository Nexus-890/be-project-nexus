package com.group5.auctionservice.reputation.config;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

public class RatingEligibilityFeignConfiguration {
    @Bean
    public Retryer ratingRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
