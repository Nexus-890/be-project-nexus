package com.group5.userservice.shared.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
//LẤY TOÀN BỘ KHOÁ TỪ nexus.iam
@ConfigurationProperties(prefix = "nexus.iam.jwt")
@Getter
@Setter
@Validated
public class JwtProperties {

    @NotBlank //DỪNG KHI THIẾU CẤU HÌNH
    private String issuer;

    @NotBlank
    private String audience;

    @NotBlank
    private String keyId;

    @NotBlank
    private String privateKeyPath;

    @NotBlank
    private String publicKeyPath;
}