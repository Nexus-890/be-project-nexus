package com.group5.shipmentservice.shared.exception;

import com.group5.common_security.SecurityProblemAdvice;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ShipmentSecurityProblemAdvice extends SecurityProblemAdvice {
    public ShipmentSecurityProblemAdvice(ObjectProvider<Tracer> tracer) {
        super(tracer.getIfAvailable());
    }

    @Override
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException exception,
                                                             HttpServletRequest request) {
        return withShipmentCode(super.handleAuthentication(exception, request));
    }

    @Override
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException exception,
                                                           HttpServletRequest request) {
        return withShipmentCode(super.handleAccessDenied(exception, request));
    }

    private ResponseEntity<ProblemDetail> withShipmentCode(ResponseEntity<ProblemDetail> response) {
        ShipmentErrorCode code = response.getStatusCode().value() == 401
                ? ShipmentErrorCode.UNAUTHENTICATED : ShipmentErrorCode.FORBIDDEN;
        if (response.getBody() != null) {
            response.getBody().setProperty("code", code.code());
            response.getBody().setDetail(code.message());
        }
        return response;
    }
}
