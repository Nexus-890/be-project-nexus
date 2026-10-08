package com.group5.common_web.filter;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER =
            "X-Trace-Id";

    private final Tracer tracer;


    public TraceIdFilter(Tracer tracer) {
        this.tracer = tracer;
    }


    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {


        /*
         * KHÔNG tạo:
         *
         * UUID.randomUUID()
         *
         * Trace ID phải lấy từ tracing system.
         */
        String traceId =
                getTraceId();


        if (traceId != null) {

            response.setHeader(
                    TRACE_ID_HEADER,
                    traceId
            );
        }


        filterChain.doFilter(
                request,
                response
        );
    }


    private String getTraceId() {

        if (tracer == null) {
            return null;
        }


        Span span =
                tracer.currentSpan();


        if (span == null) {
            return null;
        }


        return span
                .context()
                .traceId();
    }
}