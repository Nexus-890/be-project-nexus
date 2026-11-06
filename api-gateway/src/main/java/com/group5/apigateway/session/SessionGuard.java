package com.group5.apigateway.session;

import com.group5.apigateway.config.SecurityConfig.GatewaySecurityProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

// chống CSRF cho 3 endpoint dùng cookie
// trình duyệt tự đính cookie vào request, nên trang bất kỳ có thể bắt nó gọi /auth/refresh
// hai lớp chặn: header tự đặt của app (site lạ không thêm được nếu không qua CORS) và Origin nếu trình duyệt có gửi
// SameSite=Lax trên cookie chỉ là lớp thứ ba, không để nó gánh một mình
@Component
public class SessionGuard {

    private final SessionProperties properties;
    private final GatewaySecurityProperties security;

    public SessionGuard(SessionProperties properties, GatewaySecurityProperties security) {
        this.properties = properties;
        this.security = security;
    }

    public void verify(ServerWebExchange exchange) {
        var headers = exchange.getRequest().getHeaders();
        if (!properties.requiredHeaderValue().equals(headers.getFirst(properties.requiredHeaderName()))) {
            throw forbidden();
        }
        // client không phải trình duyệt thì không gửi Origin và cũng không bị site khác lừa, nên thiếu Origin vẫn cho qua
        // còn đã có Origin thì phải nằm trong danh sách origin của mình
        String origin = headers.getOrigin();
        if (origin != null && !security.getCorsAllowedOrigins().contains(origin)) {
            throw forbidden();
        }
    }

    private static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "The request did not come from the Nexus web app");
    }
}
