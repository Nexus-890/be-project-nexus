package com.group5.apigateway.session;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// cấu hình cookie refresh token, đổi được trên Config Server (nexus.security.session.*)
// mặc định luôn chọn phía an toàn, thiếu cấu hình thì cookie không bị yếu đi
@ConfigurationProperties(prefix = "nexus.security.session")
public record SessionProperties(
        @DefaultValue("nexus_rt") String cookieName,
        // path hẹp: trình duyệt chỉ gửi cookie này cho 3 endpoint session, không gửi cho mọi API
        @DefaultValue("/api/v1/auth") String cookiePath,
        @DefaultValue("true") boolean cookieSecure,
        @DefaultValue("Lax") String sameSite,
        // header tự đặt: form hay link từ site khác không thêm được, script khác origin muốn thêm
        // phải qua preflight CORS mà gateway chỉ cho origin của mình. Có header = request đến từ app mình
        @DefaultValue("X-Requested-With") String requiredHeaderName,
        @DefaultValue("nexus-web") String requiredHeaderValue,
        @DefaultValue("nexus-web") String clientId,
        @DefaultValue("5s") Duration keycloakTimeout) {
}
