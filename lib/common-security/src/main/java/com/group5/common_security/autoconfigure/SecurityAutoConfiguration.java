package com.group5.common_security.autoconfigure;

import com.group5.common_security.CurrentUserArgumentResolver;
import com.group5.common_security.JwtAuthConverter;
import com.group5.common_security.SecurityProblemAdvice;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Slf4j
@AutoConfiguration(beforeName = { //Chạy trước các auto-config security của Spring Boot,
        // để Boot thấy đã có SecurityFilterChain, JwtDecoder ...
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration"
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(NexusSecurityProperties.class)
@EnableMethodSecurity
public class SecurityAutoConfiguration {

    private static final Duration JWKS_TIMEOUT = Duration.ofSeconds(3);
    private static final String[] MONITORING_PATHS = {
            "/actuator/health", "/actuator/health/**", "/actuator/prometheus"
    };

    @Bean
    @ConditionalOnMissingBean
    public JwtDecoder jwtDecoder(NexusSecurityProperties properties) {
        //KIỂM TRA CHỮ KÝ BẰNG KEY LẤY TỪ JWKS, only receive RS256
        NexusSecurityProperties.Jwt jwt = properties.getJwt();
        String jwkSetUri = required(jwt.getJwkSetUri(), "nexus.security.jwt.jwk-set-uri");
        String issuer = required(jwt.getIssuer(), "nexus.security.jwt.issuer");
        String audience = required(jwt.getAudience(), "nexus.security.jwt.audience");

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(JWKS_TIMEOUT);
        requestFactory.setReadTimeout(JWKS_TIMEOUT);
        //KIỂM TRA HẠN DÙNG
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(requestFactory))
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audiences -> audiences != null && audiences.contains(audience))));

        log.info("Token validation enabled: issuer={}, audience={}, jwks={}", issuer, audience, jwkSetUri);
        return decoder;
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtAuthConverter jwtAuthConverter() {
        return new JwtAuthConverter();
    }

    @Bean
    @ConditionalOnMissingBean //Service nào cần khác thì tự khai Bean của minh, mặc định sẽ nhường
    public SecurityProblemAdvice securityProblemAdvice(ObjectProvider<Tracer> tracer) {
        return new SecurityProblemAdvice(tracer.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            NexusSecurityProperties properties,
            JwtAuthConverter jwtAuthConverter,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {

        AuthenticationEntryPoint entryPoint = (request, response, exception) ->
                resolve(exceptionResolver, request, response, exception, HttpServletResponse.SC_UNAUTHORIZED);
        AccessDeniedHandler accessDeniedHandler = (request, response, exception) ->
                resolve(exceptionResolver, request, response, exception, HttpServletResponse.SC_FORBIDDEN);

        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> {
                    requests.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                    requests.requestMatchers(HttpMethod.GET, MONITORING_PATHS).permitAll();
                    for (NexusSecurityProperties.PublicEndpoint endpoint : properties.getPublicEndpoints()) {
                        HttpMethod method = method(endpoint);
                        String path = required(endpoint.getPath(), "nexus.security.public-endpoints[].path");
                        requests.requestMatchers(method, path).permitAll();
                        log.info("Public endpoint: {} {}", method, path);
                    }
                    requests.anyRequest().authenticated();
                })
                .oauth2ResourceServer(server -> server
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    @Bean
    public WebMvcConfigurer currentUserArgumentResolverConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new CurrentUserArgumentResolver());
            }
        };
    }

    //lỗi ở tầng filter không đi qua controller,
    //nên hai handler chuyển nó cho HandlerExceptionResolver của Spring MVC, và SecurityProblemAdvice
    private static void resolve(HandlerExceptionResolver exceptionResolver,
                                HttpServletRequest request,
                                HttpServletResponse response,
                                Exception exception,
                                int fallbackStatus) throws IOException {
        if (exceptionResolver.resolveException(request, response, null, exception) == null) {
            response.sendError(fallbackStatus);
        }
    }

    private static HttpMethod method(NexusSecurityProperties.PublicEndpoint endpoint) {
        String name = required(endpoint.getMethod(), "nexus.security.public-endpoints[].method")
                .toUpperCase(Locale.ROOT);
        HttpMethod method = HttpMethod.valueOf(name);
        if (!Arrays.asList(HttpMethod.values()).contains(method)) {
            throw new IllegalStateException(
                    "nexus.security.public-endpoints[].method is not an HTTP method: " + name);
        }
        return method;
    }

    private static String required(String value, String property) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(property + " must be set");
        }
        return value.trim();
    }
}