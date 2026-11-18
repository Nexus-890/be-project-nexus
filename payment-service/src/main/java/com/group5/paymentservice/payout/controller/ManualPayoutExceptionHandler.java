package com.group5.paymentservice.payout.controller;

import com.group5.common_core.exception.DomainException;
import com.group5.paymentservice.shared.exception.PaymentErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.net.URI;

/** Keep database/upstream details out of private financial endpoints. */
@RestControllerAdvice(
        assignableTypes = {ManualPayoutController.class, SellerPayoutReceiptController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ManualPayoutExceptionHandler {
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetail> domain(DomainException error, HttpServletRequest request) {
        return response(
                error.getCode(),
                error.getErrorCode(),
                error.getMessage(),
                request,
                error.getProperties());
    }

    @ExceptionHandler({
        BindException.class,
        ConstraintViolationException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingRequestHeaderException.class,
        MissingServletRequestParameterException.class,
        MissingServletRequestPartException.class
    })
    public ResponseEntity<ProblemDetail> invalid(Exception error, HttpServletRequest request) {
        return named(PaymentErrorCode.MANUAL_PAYOUT_INVALID_REQUEST, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> conflict(Exception error, HttpServletRequest request) {
        return named(PaymentErrorCode.MANUAL_PAYOUT_CONFLICT, request);
    }

    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    public ResponseEntity<ProblemDetail> unavailable(Exception error, HttpServletRequest request) {
        return named(PaymentErrorCode.MANUAL_PAYOUT_UNAVAILABLE, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> forbidden(Exception error, HttpServletRequest request) {
        return named(PaymentErrorCode.MANUAL_PAYOUT_FORBIDDEN, request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> tooLarge(Exception error, HttpServletRequest request) {
        return response(
                413,
                "PAYMENT_MANUAL_PAYOUT_EVIDENCE_TOO_LARGE",
                "Evidence file exceeds the allowed size",
                request,
                java.util.Map.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> unsupported(Exception error, HttpServletRequest request) {
        return response(
                415,
                "PAYMENT_MANUAL_PAYOUT_EVIDENCE_UNSUPPORTED",
                "Evidence content type is not supported",
                request,
                java.util.Map.of());
    }

    private ResponseEntity<ProblemDetail> named(PaymentErrorCode code, HttpServletRequest request) {
        return response(
                code.status().value(), code.code(), code.message(), request, java.util.Map.of());
    }

    private ResponseEntity<ProblemDetail> response(
            int status,
            String code,
            String detail,
            HttpServletRequest request,
            java.util.Map<String, Object> properties) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setType(URI.create("urn:problem:manual-payout"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        properties.forEach(
                (name, value) -> {
                    if (!java.util.Set.of("type", "title", "status", "detail", "instance", "code")
                            .contains(name)) problem.setProperty(name, value);
                });
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .body(problem);
    }
}
