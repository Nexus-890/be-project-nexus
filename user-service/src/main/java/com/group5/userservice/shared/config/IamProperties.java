package com.group5.userservice.shared.config;

import jakarta.validation.constraints.Max;
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

    @Min(1)
    @Max(Short.MAX_VALUE)//32767
    private int maxLoginAttempts = 5;

    @Min(1)
    private long lockDurationMinutes = 15;


}
