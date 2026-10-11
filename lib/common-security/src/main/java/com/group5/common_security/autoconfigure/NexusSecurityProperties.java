package com.group5.common_security.autoconfigure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "nexus.security")
public class NexusSecurityProperties {

    private Jwt jwt = new Jwt();
    private List<PublicEndpoint> publicEndpoints = new ArrayList<>();

    @Getter
    @Setter
    public static class Jwt {

        private String issuer;
        private String audience;
        private String jwkSetUri;
    }

    @Getter
    @Setter
    public static class PublicEndpoint {

        private String method;
        private String path;
    }
}