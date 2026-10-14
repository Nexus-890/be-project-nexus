package com.group5.apigateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.config.name=api-gateway-test",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1/.well-known/jwks.json",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=user-service",
        "spring.security.oauth2.resourceserver.jwt.audiences[0]=nexus-services"
})
class ApiGatewayApplicationTests {

	@Test
	void contextLoads() {
	}

}
