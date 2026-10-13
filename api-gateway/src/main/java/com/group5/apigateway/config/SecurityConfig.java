package com.group5.apigateway.config;

import io.micrometer.tracing.Tracer;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.DelegatingJwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtGrantedAuthoritiesConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties({OAuth2ResourceServerProperties.class, SecurityConfig.GatewaySecurityProperties.class})
public class SecurityConfig {

    private static final String TYPE_PREFIX = "https://nexus/errors/";
    private static final String UUID_REGEX = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    @Bean
    SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http, GatewaySecurityProperties properties,
            ServerCodecConfigurer codecs, ObjectProvider<Tracer> tracer,
            ReactiveJwtAuthenticationConverter jwtAuthenticationConverter) {

        ServerAuthenticationEntryPoint entryPoint = (exchange, exception) -> {
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            return writeProblem(exchange, codecs, tracer.getIfAvailable(), HttpStatus.UNAUTHORIZED);
        };
        ServerAccessDeniedHandler deniedHandler = (exchange, exception) ->
                writeProblem(exchange, codecs, tracer.getIfAvailable(), HttpStatus.FORBIDDEN);

        List<Endpoint> endpoints = properties.endpoints();
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .authorizeExchange(exchanges -> {
                    endpoints.stream()
                            .filter(Endpoint::isPublic)
                            .forEach(endpoint -> exchanges
                                    .pathMatchers(endpoint.method(), matcherPath(endpoint.path()))
                                    .permitAll());
                    endpoints.stream()
                            .filter(endpoint -> !endpoint.isPublic())
                            .forEach(endpoint -> exchanges
                                    .pathMatchers(endpoint.method(), matcherPath(endpoint.path()))
                                    .hasAuthority(endpoint.privilege()));
                    exchanges.anyExchange().authenticated();
                })
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .build();
    }

    @Bean
    ReactiveJwtDecoder reactiveJwtDecoder(
            OAuth2ResourceServerProperties properties) {
        var jwt = properties.getJwt();
        Assert.hasText(jwt.getJwkSetUri(), "JWT jwk-set-uri is required");
        Assert.hasText(jwt.getIssuerUri(), "JWT issuer-uri is required");
        Assert.notEmpty(jwt.getAudiences(), "JWT audiences are required");

        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwt.getJwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(jwt.getIssuerUri()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        tokenAudiences -> tokenAudiences != null
                                && tokenAudiences.containsAll(jwt.getAudiences())),
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, expiry -> expiry != null)));
        return token -> Mono.defer(() -> {
            // Reject aliases that change unused base64url bits, even when the
            // decoded signature stays the same after changing its last character.
            try {
                String[] parts = token.split("\\.", -1);
                if (parts.length != 3) {
                    return Mono.error(new BadJwtException("Invalid JWT encoding"));
                }
                for (String part : parts) {
                    String canonical = Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(Base64.getUrlDecoder().decode(part));
                    if (!canonical.equals(part)) {
                        return Mono.error(new BadJwtException("Invalid JWT encoding"));
                    }
                }
            } catch (IllegalArgumentException exception) {
                return Mono.error(new BadJwtException("Invalid JWT encoding"));
            }
            return decoder.decode(token);
        });
    }

    @Bean
    ReactiveJwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("privileges");
        authoritiesConverter.setAuthorityPrefix("");
        JwtGrantedAuthoritiesConverter rolesConverter = new JwtGrantedAuthoritiesConverter();
        rolesConverter.setAuthoritiesClaimName("roles");
        rolesConverter.setAuthorityPrefix("ROLE_");

        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                new ReactiveJwtGrantedAuthoritiesConverterAdapter(
                        new DelegatingJwtGrantedAuthoritiesConverter(rolesConverter, authoritiesConverter)));
        return converter;
    }

    // UUID constraints prevent /products/mine and future literal routes from
    // inheriting the public detail endpoint policy.
    static String matcherPath(String path) {
        return path.replace("{id}", "{id:" + UUID_REGEX + "}");
    }

    private static Mono<Void> writeProblem(
            ServerWebExchange exchange,
            ServerCodecConfigurer codecs, Tracer tracer,
            HttpStatus status) {

        ProblemDetail problem = ProblemDetail.forStatus(status);
        String path = exchange.getRequest().getPath().value();
        problem.setType(URI.create(TYPE_PREFIX + status.name().toLowerCase().replace('_', '-')));
        problem.setTitle(status.getReasonPhrase());
        problem.setDetail(status == HttpStatus.UNAUTHORIZED
                ? "A valid bearer token is required" : "The required privilege is missing");
        problem.setInstance(URI.create(path));
        var span = tracer == null ? null : tracer.currentSpan();
        problem.setProperty("traceId", span == null
                ? exchange.getAttributes().computeIfAbsent("nexus.security.traceId", key -> UUID.randomUUID().toString())
                : span.context().traceId());

        return ServerResponse.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).bodyValue(problem)
                .flatMap(response -> response.writeTo(exchange, new ServerResponse.Context() {
                    @Override
                    public List<HttpMessageWriter<?>> messageWriters() {
                        return codecs.getWriters();
                    }

                    @Override
                    public List<ViewResolver> viewResolvers() {
                        return List.of();
                    }
                }));
    }

    // Same configuration contract as common-security's NexusSecurityProperties,
    // without importing its servlet-only auto-configuration into WebFlux.
    @Getter
    @Setter
    @ConfigurationProperties(prefix = "nexus.security")
    public static class GatewaySecurityProperties {
        private List<Endpoint> publicEndpoints = new ArrayList<>();
        private List<Endpoint> protectedEndpoints = new ArrayList<>();

        List<Endpoint> endpoints() {
            Set<String> pairs = new HashSet<>();
            publicEndpoints.forEach(endpoint ->
                    Assert.isTrue(endpoint.isPublic(), "Public endpoints must not declare a privilege"));
            protectedEndpoints.forEach(endpoint ->
                    Assert.hasText(endpoint.privilege(), "Protected endpoint privilege is required"));
            return Stream.concat(publicEndpoints.stream(), protectedEndpoints.stream())
                    .peek(endpoint -> {
                        Assert.isTrue(Arrays.asList(HttpMethod.values()).contains(endpoint.method()),
                                "Endpoint method must be a standard HTTP method");
                        Assert.hasText(endpoint.path(), "Endpoint path is required");
                        Assert.isTrue(endpoint.path().startsWith("/"), "Endpoint path must start with /");
                        Assert.isTrue(pairs.add(endpoint.method() + " " + endpoint.path()),
                                "Duplicate endpoint method/path pair");
                    })
                    .toList();
        }
    }

    public record Endpoint(HttpMethod method, String path, String privilege) {
        boolean isPublic() {
            return privilege == null;
        }
    }
}
