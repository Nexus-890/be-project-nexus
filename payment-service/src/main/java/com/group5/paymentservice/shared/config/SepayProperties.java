package com.group5.paymentservice.shared.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "nexus.payment.sepay")
public record SepayProperties(

        @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{2,32}$")
        String bankId,

        @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{1,19}$")
        String bankAccount,

        @NotBlank @Size(max = 100) String accountName,

        @NotBlank @Pattern(regexp = "^(compact|qronly|standee)$")
        String vietqrTemplate,

        @DefaultValue("") @Pattern(regexp = "^[A-Z0-9]{0,16}$")
        String transferContentPrefix) {
}
