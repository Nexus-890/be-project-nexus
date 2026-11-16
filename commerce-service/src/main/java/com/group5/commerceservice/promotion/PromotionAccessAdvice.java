package com.group5.commerceservice.promotion;

import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_web.exception.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {MegaSaleEventController.class, VoucherController.class})
class PromotionAccessAdvice {
    private final GlobalExceptionHandler errors;

    PromotionAccessAdvice(GlobalExceptionHandler errors) {
        this.errors = errors;
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> forbidden(HttpServletRequest request) {
        return errors.handleDomainException(new DomainException(CommerceErrorCode.FORBIDDEN), request);
    }
}
