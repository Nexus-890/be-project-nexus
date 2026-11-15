package com.group5.auctionservice.auction;

import com.group5.common_core.model.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@FeignClient(name = "inventory-service", contextId = "auctionInventory",
        url = "${nexus.auction.inventory-base-url:}")
public interface AuctionInventoryClient {
    @GetMapping("/api/v1/inventory/{skuId}")
    ApiResponse<Stock> available(@PathVariable("skuId") UUID skuId);

    record Stock(UUID skuId, Integer available) {
    }
}
