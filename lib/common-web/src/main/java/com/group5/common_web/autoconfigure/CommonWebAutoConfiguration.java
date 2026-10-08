package com.group5.common_web.autoconfigure;

import com.group5.common_web.exception.GlobalExceptionHandler;
import com.group5.common_web.filter.TraceIdFilter;

import io.micrometer.tracing.Tracer;

import org.springframework.beans.factory.ObjectProvider;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

import org.springframework.context.annotation.Bean;


@AutoConfiguration
@ConditionalOnWebApplication(
        type = ConditionalOnWebApplication.Type.SERVLET
)
public class CommonWebAutoConfiguration {


    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler(
            ObjectProvider<Tracer> tracerProvider
    ) {

        Tracer tracer =
                tracerProvider.getIfAvailable();


        return new GlobalExceptionHandler(
                tracer
        );
    }


    @Bean
    @ConditionalOnMissingBean
    public TraceIdFilter traceIdFilter(
            ObjectProvider<Tracer> tracerProvider
    ) {

        Tracer tracer =
                tracerProvider.getIfAvailable();


        return new TraceIdFilter(
                tracer
        );
    }
}