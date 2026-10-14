package com.group5.userservice.shared.config;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
//LẤY TOÀN BỘ KHOÁ TỪ nexus.iam
@ConfigurationProperties(prefix = "nexus.iam")
@Getter
@Setter
@Validated
public class IamProperties {

    @Min(1) //Protect wrong value when starting
    private long accessTokenExpiryMinutes = 60;

    private String defaultUserRole = "BUYER";

    private boolean allowSelfRegistration = true;
}
