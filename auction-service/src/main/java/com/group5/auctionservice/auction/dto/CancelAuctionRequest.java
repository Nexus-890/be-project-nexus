package com.group5.auctionservice.auction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelAuctionRequest(@NotBlank @Size(max = 255) String reason) { }
