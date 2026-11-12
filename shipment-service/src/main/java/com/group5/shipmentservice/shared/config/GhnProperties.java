package com.group5.shipmentservice.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.net.URI;

@ConfigurationProperties(prefix = "nexus.shipping.ghn")
@Validated
public record GhnProperties(
        @DefaultValue("https://dev-online-gateway.ghn.vn") URI baseUrl,
        @DefaultValue("0") Integer shopId,
        @DefaultValue("1") @NotNull @Min(1) @Max(2) Integer paymentTypeId,
        @DefaultValue("KHONGCHOXEMHANG") @NotNull RequiredNote requiredNote,
        @DefaultValue("1500") @Min(1) int httpTimeoutMs,
        @DefaultValue("300000") @Min(0) long masterDataCacheTtlMs) {
    public enum RequiredNote {
        KHONGCHOXEMHANG,
        CHOXEMHANGKHONGTHU,
        CHOTHUHANG
    }
}
