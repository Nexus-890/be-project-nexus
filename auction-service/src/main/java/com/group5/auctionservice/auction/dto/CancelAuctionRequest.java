package com.group5.auctionservice.auction.dto;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

public record CancelAuctionRequest(@NotBlank @CodePointLength(max = 255) String reason) { }
