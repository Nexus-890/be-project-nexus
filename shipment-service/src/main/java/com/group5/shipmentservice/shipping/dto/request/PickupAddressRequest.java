package com.group5.shipmentservice.shipping.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PickupAddressRequest(
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,8}") String provinceCode,
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,8}") String wardCode,
        @NotBlank @Size(max = 255) String line1) {
}
