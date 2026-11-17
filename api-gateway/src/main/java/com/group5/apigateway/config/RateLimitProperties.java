package com.group5.apigateway.config;

public class RateLimitProperties {

    private int requestsPerMinute = 100;
    private int auctionReadRequestsPerMinute = 600;

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public void setRequestsPerMinute(int requestsPerMinute) {
        if (requestsPerMinute < 1) {
            throw new IllegalArgumentException(
                    "requests-per-minute must be greater than zero");
        }
        this.requestsPerMinute = requestsPerMinute;
    }

    public int getAuctionReadRequestsPerMinute() {
        return auctionReadRequestsPerMinute;
    }

    public void setAuctionReadRequestsPerMinute(int auctionReadRequestsPerMinute) {
        if (auctionReadRequestsPerMinute < 1) {
            throw new IllegalArgumentException("auction-read-requests-per-minute must be greater than zero");
        }
        this.auctionReadRequestsPerMinute = auctionReadRequestsPerMinute;
    }
}
