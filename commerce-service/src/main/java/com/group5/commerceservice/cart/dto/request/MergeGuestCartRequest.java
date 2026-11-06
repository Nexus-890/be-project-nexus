package com.group5.commerceservice.cart.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MergeGuestCartRequest(@NotBlank @Size(max = 255) String guestToken) { }
