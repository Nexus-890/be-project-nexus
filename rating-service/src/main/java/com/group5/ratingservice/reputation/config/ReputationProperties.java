package com.group5.ratingservice.reputation.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "nexus.reputation")
public class ReputationProperties {
    @NotBlank @Size(max = 60) private String version;
    @Min(0) @Max(100) private int ratingWeight = 30;
    @Min(0) @Max(100) private int successWeight = 20;
    @Min(0) @Max(100) private int minimumToBid = 40;
    @Min(0) @Max(100) private int minimumToSell = 50;
    @Min(1) @Max(100) private int timeoutPenalty = 10;
    @NotNull @Min(0) @Max(98) private Integer basicMax;
    @NotNull @Min(1) @Max(99) private Integer trustedMax;

    @AssertTrue
    public boolean isThresholdsOrdered() {
        return basicMax == null || trustedMax == null || basicMax < trustedMax;
    }
}
