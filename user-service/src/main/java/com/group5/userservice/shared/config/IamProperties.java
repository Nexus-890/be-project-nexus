package com.group5.userservice.shared.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
//LẤY TOÀN BỘ KHOÁ TỪ nexus.iam
@ConfigurationProperties(prefix = "nexus.iam")
@Getter
@Setter
public class IamProperties {

    private boolean userSoftDelete = true;

    private String defaultUserRole = "BUYER";

    private boolean allowSelfRegistration = true;
}
