package com.group5.configservice.systemconfig;

import com.group5.common_security.JwtAuthConverter;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.cloud.config.server.environment.NativeEnvironmentProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.beans.factory.annotation.Qualifier;

/** Chỉ API admin dùng JWT; giao thức đọc Config Server vẫn dùng được trước khi IAM khởi động. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SystemConfigConfiguration {
    /** Thay native repository bằng lớp kế thừa có override bền vững. */
    @Bean
    RuntimeEnvironmentRepository runtimeEnvironmentRepository(ConfigurableEnvironment environment,
            ObjectProvider<ObservationRegistry> observations, SystemConfigStore store) {
        NativeEnvironmentProperties properties = Binder.get(environment)
                .bind("spring.cloud.config.server.native", NativeEnvironmentProperties.class)
                .orElseGet(NativeEnvironmentProperties::new);
        RuntimeEnvironmentRepository repository = new RuntimeEnvironmentRepository(environment, properties,
                observations.getIfAvailable(() -> ObservationRegistry.NOOP), store);
        repository.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
        return repository;
    }

    /** Bảo vệ API ghi/đọc admin, không làm thay đổi JSON của giao thức Config Server. */
    @Bean
    SecurityFilterChain configSecurity(HttpSecurity http, JwtAuthConverter converter,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
        var entry = (org.springframework.security.web.AuthenticationEntryPoint) (request, response, exception) ->
                resolver.resolveException(request, response, null, exception);
        var denied = (org.springframework.security.web.access.AccessDeniedHandler) (request, response, exception) ->
                resolver.resolveException(request, response, null, exception);
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/system/configs", "/api/v1/system/configs/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/**").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(entry).accessDeniedHandler(denied))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entry).accessDeniedHandler(denied));
        return http.build();
    }
}
