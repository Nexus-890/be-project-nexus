package com.group5.userservice.address.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AddressRequest(
        @NotBlank(message = "Recipient name is required")
        @Size(max = 150, message = "Recipient name must contain at most 150 characters")
        String recipientName,

        @NotBlank(message = "Phone is required")
        @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "Invalid phone number")
        String phone,

        @NotBlank(message = "Address line is required")
        @Size(max = 255, message = "Address line must contain at most 255 characters")
        String addressLine,

        @NotBlank(message = "Province code is required")
        @Pattern(regexp = "^[1-9][0-9]{0,8}$", message = "Invalid province code")
        String provinceCode,

        @NotBlank(message = "Province name is required")
        @Size(max = 100, message = "Province name must contain at most 100 characters")
        String provinceName,

        @NotBlank(message = "Ward code is required")
        @Pattern(regexp = "^[1-9][0-9]{0,8}$", message = "Invalid ward code")
        String wardCode,

        @NotBlank(message = "Ward name is required")
        @Size(max = 100, message = "Ward name must contain at most 100 characters")
        String wardName,

        Boolean isDefault
) {
    public AddressRequest {
        recipientName = trim(recipientName);
        phone = phone == null ? null : phone.trim().replaceAll("[\\s()\\-]", "");
        addressLine = trim(addressLine);
        provinceCode = trim(provinceCode);
        provinceName = trim(provinceName);
        wardCode = trim(wardCode);
        wardName = trim(wardName);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
