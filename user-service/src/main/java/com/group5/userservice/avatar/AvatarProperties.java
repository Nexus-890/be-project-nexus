package com.group5.userservice.avatar;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "nexus.avatar")
public class AvatarProperties {
    private String supabaseUrl = "";
    private String serviceKey = "";

    @NotBlank
    @Pattern(regexp = "[a-zA-Z0-9_-]+")
    private String bucket = "product-images";

    private DataSize maxFileSize = DataSize.ofMegabytes(2);

    @NotNull
    private Duration timeout = Duration.ofSeconds(15);

    @AssertTrue(message = "max-file-size must be positive and fit in memory")
    public boolean isMaxFileSizeValid() {
        return maxFileSize != null && maxFileSize.toBytes() > 0 && maxFileSize.toBytes() < Integer.MAX_VALUE;
    }
}
