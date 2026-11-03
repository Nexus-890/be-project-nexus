package com.group5.common_web.exception;

import com.group5.common_core.constant.CommonErrorCode;
import com.group5.common_core.constant.ErrorCode;
import com.group5.common_core.exception.DomainException;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final Tracer tracer;
    public GlobalExceptionHandler(Tracer tracer) { this.tracer = tracer; }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetail> handleDomainException(DomainException error, HttpServletRequest request) {
        return response(error.getCode(), error.getErrorCode(), "domain-error", error.getMessage(), request, null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableRequestBody(HttpMessageNotReadableException error, HttpServletRequest request) {
        return response(CommonErrorCode.INVALID_REQUEST_BODY, "invalid-request-body", request, null);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ProblemDetail> handleValidation(BindException error, HttpServletRequest request) {
        Map<String, ValidationError> fields = new LinkedHashMap<>();
        for (FieldError field : error.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(field.getField(), new ValidationError(field.getField(), validationCode(field.getCode()),
                    field.getDefaultMessage() == null ? "Invalid value" : field.getDefaultMessage()));
        }
        error.getBindingResult().getGlobalErrors().forEach(object -> fields.putIfAbsent(object.getObjectName(),
                new ValidationError(object.getObjectName(), validationCode(object.getCode()), object.getDefaultMessage())));
        return response(CommonErrorCode.VALIDATION_FAILED, "validation-error", request, List.copyOf(fields.values()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException error, HttpServletRequest request) {
        List<ValidationError> errors = error.getConstraintViolations().stream().map(v -> new ValidationError(
                v.getPropertyPath().toString(), validationCode(v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName()),
                v.getMessage())).toList();
        return response(CommonErrorCode.VALIDATION_FAILED, "validation-error", request, errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleMethodValidation(HandlerMethodValidationException error, HttpServletRequest request) {
        if (error.isForReturnValue()) return handleUnexpectedException(error, request);
        List<ValidationError> errors = error.getParameterValidationResults().stream().flatMap(result ->
                result.getResolvableErrors().stream().map(e -> new ValidationError(
                        result.getMethodParameter().getParameterName() == null ? "parameter" : result.getMethodParameter().getParameterName(),
                        "INVALID_PARAMETER", e.getDefaultMessage()))).toList();
        return response(CommonErrorCode.VALIDATION_FAILED, "validation-error", request, errors);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ProblemDetail> handleTypeMismatch(Exception error, HttpServletRequest request) {
        String name = error instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName()
                : ((MissingServletRequestParameterException) error).getParameterName();
        return response(CommonErrorCode.INVALID_PARAMETER, "invalid-parameter", request,
                List.of(new ValidationError(name, "INVALID_PARAMETER", "A valid value is required")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpectedException(Exception error, HttpServletRequest request) {
        if (error instanceof ErrorResponse framework) {
            int status = framework.getStatusCode().value();
            if (status < 500) {
                ResponseEntity<ProblemDetail> response = response(status, CommonErrorCode.forStatus(status).code(),
                        CommonErrorCode.forStatus(status).code().toLowerCase().replace('_', '-'),
                        CommonErrorCode.forStatus(status).message(), request, null);
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(framework.getHeaders());
                headers.putAll(response.getHeaders());
                return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
            }
        }
        log.error("Unexpected exception. traceId={}", traceId(), error);
        return response(CommonErrorCode.INTERNAL_ERROR, "internal-server-error", request, null);
    }

    private ResponseEntity<ProblemDetail> response(ErrorCode code, String type, HttpServletRequest request, List<ValidationError> errors) {
        return response(code.status().value(), code.code(), type, code.message(), request, errors);
    }

    private ResponseEntity<ProblemDetail> response(int status, String code, String type, String detail,
                                                   HttpServletRequest request, List<ValidationError> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail == null ? "An error occurred" : detail);
        problem.setType(URI.create("urn:problem:" + type));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        String traceId = traceId();
        if (traceId != null) problem.setProperty("traceId", traceId);
        if (errors != null) problem.setProperty("errors", errors);
        return ResponseEntity.status(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    private String traceId() {
        var span = tracer == null ? null : tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }

    private static String validationCode(String constraint) {
        if (constraint == null) return "INVALID_VALUE";
        return "VALIDATION_" + constraint.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT);
    }

    public record ValidationError(String field, String code, String detail) { }
}
