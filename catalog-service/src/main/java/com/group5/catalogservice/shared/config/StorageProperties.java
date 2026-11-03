package com.group5.catalogservice.shared.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Validated
@Configuration(proxyBeanMethods = false)
@ConfigurationProperties(prefix = "nexus.storage")
public class StorageProperties {
    private String supabaseUrl = "";
    private String serviceKey = "";

    @NotBlank
    @Pattern(regexp = "[a-zA-Z0-9_-]+")
    private String bucket = "product-images";

    private DataSize maxFileSize = DataSize.ofMegabytes(5);

    @Bean
    MultipartConfigElement multipartConfigElement() {
        long limit = maxFileSize.toBytes();
        // Multipart framing needs space in addition to the file itself.
        return new MultipartConfigElement("", limit, limit + 1024 * 1024, 0);
    }

    @AssertTrue(message = "max-file-size must be positive and fit in memory")
    public boolean isMaxFileSizeValid() {
        return maxFileSize != null && maxFileSize.toBytes() > 0
                && maxFileSize.toBytes() < Integer.MAX_VALUE;
    }
}
