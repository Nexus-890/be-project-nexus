package com.group5.ratingservice.shared.exception;

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
public class RatingSecurityProblemAdvice extends SecurityProblemAdvice {
    public RatingSecurityProblemAdvice(ObjectProvider<Tracer> tracer) {
        super(tracer.getIfAvailable());
    }

    @Override
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException exception,
                                                             HttpServletRequest request) {
        return withRatingCode(super.handleAuthentication(exception, request));
    }

    @Override
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException exception,
                                                           HttpServletRequest request) {
        return withRatingCode(super.handleAccessDenied(exception, request));
    }

    private ResponseEntity<ProblemDetail> withRatingCode(ResponseEntity<ProblemDetail> response) {
        RatingErrorCode code = response.getStatusCode().value() == 401
                ? RatingErrorCode.UNAUTHENTICATED : RatingErrorCode.FORBIDDEN;
        if (response.getBody() != null) {
            response.getBody().setProperty("code", code.code());
            response.getBody().setDetail(code.message());
        }
        return response;
    }
}
