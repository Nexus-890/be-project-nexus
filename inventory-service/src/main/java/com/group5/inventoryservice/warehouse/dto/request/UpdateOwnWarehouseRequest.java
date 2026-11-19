package com.group5.inventoryservice.warehouse.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateOwnWarehouseRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 500) String address,
        @Pattern(regexp = "[1-9][0-9]{0,8}") String provinceCode,
        @Pattern(regexp = "[1-9][0-9]{0,8}") String wardCode) {

    public UpdateOwnWarehouseRequest(String name, String address) {
        this(name, address, null, null);
    }

    @AssertTrue(message = "provinceCode and wardCode must be sent together")
    public boolean isPickupAreaComplete() {
        return (provinceCode == null) == (wardCode == null);
    }

    @AssertTrue(message = "A pickup area needs a street address of at most 255 characters")
    public boolean isPickupAddressUsable() {
        return provinceCode == null || (address != null && !address.isBlank() && address.strip().length() <= 255);
    }
}
