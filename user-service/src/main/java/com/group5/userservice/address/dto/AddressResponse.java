package com.group5.userservice.address.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.group5.userservice.address.UserAddress;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AddressResponse(
        UUID id,
        String recipientName,
        String phone,
        String addressLine,
        String provinceCode,
        String provinceName,
        String wardCode,
        String wardName,
        @JsonProperty("isDefault") boolean isDefault,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static AddressResponse from(UserAddress address) {
        return new AddressResponse(address.getId(), address.getRecipientName(), address.getPhone(),
                address.getAddressLine(), address.getProvinceCode(), address.getProvinceName(),
                address.getWardCode(), address.getWardName(), address.isDefaultAddress(),
                address.getCreatedAt(), address.getUpdatedAt());
    }
}
