package com.group5.apigateway.config;

import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

@Slf4j
@Component
@Order(-2) //DEFAULT CHẠY TRƯỚC BỘ XỬ LÝ LỖI CỦA SPRINGBOOT -1
public class GatewayErrorHandler implements WebExceptionHandler {

    private static final String TYPE_PREFIX = "https://nexus/errors/";

    private final ServerResponse.Context responseContext;

    public GatewayErrorHandler(ServerCodecConfigurer codecConfigurer) {
        List<HttpMessageWriter<?>> writers = codecConfigurer.getWriters();
        this.responseContext = new ServerResponse.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return writers;
            }

            @Override
            public List<ViewResolver> viewResolvers() {
                return List.of();
            }
        };
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }

        HttpStatus status = resolveStatus(ex);
        String path = exchange.getRequest().getPath().value();
        if (status.is5xxServerError()) {
            log.warn("{} {} failed with {}: {}", exchange.getRequest().getMethod(), path, status.value(), ex.toString());
        }

        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(TYPE_PREFIX + status.name().toLowerCase().replace('_', '-')));
        problem.setTitle(status.getReasonPhrase());
        problem.setDetail(detail(status));
        problem.setInstance(URI.create(path));
        problem.setProperty("traceId", traceId(exchange));

        return ServerResponse.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .headers(headers -> {
                    // Giữ thời gian chờ từ TTL Redis khi limiter phát sinh lỗi 429.
                    if (ex instanceof HttpClientErrorException.TooManyRequests error) {
                        HttpHeaders sourceHeaders = error.getResponseHeaders();

                        if (sourceHeaders != null) {
                            String retryAfter =
                                    sourceHeaders.getFirst(HttpHeaders.RETRY_AFTER);

                            if (retryAfter != null) {
                                headers.set(HttpHeaders.RETRY_AFTER, retryAfter);
                            }
                        }
                    }
                })
                .bodyValue(problem)
                .flatMap(response -> response.writeTo(exchange, responseContext));
    }

    private HttpStatus resolveStatus(Throwable ex) {
        //NOT HAVE ROUTE SPRING THROW ERROR CODE          404
        //EUREKA NOT HAVE INSTANCE -> GATEWAY THROW       503
        //SERVICE DOWN | EUREKA CHƯA KỊP UPDATE           503

        if (ex instanceof HttpClientErrorException.TooManyRequests) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }

        if (ex instanceof ResponseStatusException statusException) {
            HttpStatusCode code = statusException.getStatusCode();
            HttpStatus status = HttpStatus.resolve(code.value());
            return status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
        }
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
                return HttpStatus.SERVICE_UNAVAILABLE;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private String detail(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "No route matches this path";
            case SERVICE_UNAVAILABLE -> "The service behind this path is not available";
            case GATEWAY_TIMEOUT -> "The service behind this path did not answer in time";
            case TOO_MANY_REQUESTS -> "Too many requests from this IP; retry after the indicated delay";
            default -> status.getReasonPhrase();
        };
    }

    private String traceId(ServerWebExchange exchange) {
        //CÁI NÀY TỪ REQUEST ĐANG XỬ LÝ, GỬI LÊN ZIPKIN
        return ServerRequestObservationContext.findCurrent(exchange.getAttributes())
                .map(context -> (TracingContext) context.get(TracingContext.class))
                .map(TracingContext::getSpan)
                .map(span -> span.context().traceId())
                .orElse(null);
    }
}