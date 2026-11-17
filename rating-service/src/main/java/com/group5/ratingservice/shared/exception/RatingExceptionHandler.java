package com.group5.ratingservice.shared.exception;

import com.group5.common_web.exception.GlobalExceptionHandler;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RatingExceptionHandler extends GlobalExceptionHandler {
    public RatingExceptionHandler(ObjectProvider<Tracer> tracer) {
        super(tracer.getIfAvailable());
    }

    @Override
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableRequestBody(HttpMessageNotReadableException error,
                                                                    HttpServletRequest request) {
        return remap(super.handleUnreadableRequestBody(error, request), RatingErrorCode.INVALID_REQUEST_BODY);
    }

    @Override
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ProblemDetail> handleValidation(BindException error, HttpServletRequest request) {
        return remap(super.handleValidation(error, request), RatingErrorCode.VALIDATION_FAILED);
    }

    @Override
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException error,
                                                                   HttpServletRequest request) {
        return remap(super.handleConstraintViolation(error, request), RatingErrorCode.VALIDATION_FAILED);
    }

    @Override
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleMethodValidation(HandlerMethodValidationException error,
                                                               HttpServletRequest request) {
        if (error.isForReturnValue()) {
            return handleUnexpectedException(error, request);
        }
        return remap(super.handleMethodValidation(error, request), RatingErrorCode.VALIDATION_FAILED);
    }

    @Override
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ProblemDetail> handleTypeMismatch(Exception error, HttpServletRequest request) {
        return remap(super.handleTypeMismatch(error, request), RatingErrorCode.INVALID_PARAMETER);
    }

    @Override
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpectedException(Exception error, HttpServletRequest request) {
        ResponseEntity<ProblemDetail> response = super.handleUnexpectedException(error, request);
        RatingErrorCode code = RatingErrorCode.forStatus(response.getStatusCode().value());
        return remap(response, code);
    }

    private ResponseEntity<ProblemDetail> remap(ResponseEntity<ProblemDetail> response, RatingErrorCode code) {
        if (response.getBody() != null) {
            response.getBody().setProperty("code", code.code());
            response.getBody().setDetail(code.message());
        }
        return response;
    }
}
