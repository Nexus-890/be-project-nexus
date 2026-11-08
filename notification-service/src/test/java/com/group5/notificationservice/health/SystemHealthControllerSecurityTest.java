package com.group5.notificationservice.health;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringJUnitConfig(SystemHealthControllerSecurityTest.SecurityConfig.class)
class SystemHealthControllerSecurityTest {

    @Autowired private SystemHealthController controller;
    @Autowired private SystemHealthService service;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        Mockito.reset(service);
    }

    @Test
    void aBuyerCannotReadSystemHealth() {
        authenticate("ROLE_BUYER", "NOTIFICATION.VIEW");

        assertThatThrownBy(controller::systemHealth).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(controller::brokers).isInstanceOf(AccessDeniedException.class);
        verify(service, never()).systemHealth();
        verify(service, never()).brokers();
    }

    @Test
    void anAdminReadsSystemHealth() {
        authenticate("ROLE_ADMIN", "SYSTEM.HEALTH.VIEW");

        controller.systemHealth();
        controller.brokers();

        verify(service).systemHealth();
        verify(service).brokers();
    }

    @Test
    void supportStaffReadsSystemHealth() {
        authenticate("ROLE_SUPPORT_STAFF", "SYSTEM.HEALTH.VIEW");

        controller.systemHealth();

        verify(service).systemHealth();
    }

    private static void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "caller", "", AuthorityUtils.createAuthorityList(authorities)));
    }

    @Configuration
    @EnableMethodSecurity
    static class SecurityConfig {

        @Bean
        SystemHealthService systemHealthService() {
            return Mockito.mock(SystemHealthService.class);
        }

        @Bean
        SystemHealthController systemHealthController(SystemHealthService systemHealthService) {
            return new SystemHealthController(systemHealthService);
        }
    }
}
