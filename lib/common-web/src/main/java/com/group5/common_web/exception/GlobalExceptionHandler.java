package com.group5.common_web.exception;

import com.group5.common_core.constant.ErrorCode;
import com.group5.common_core.exception.DomainException;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import org.springframework.validation.FieldError;

import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Tracer tracer;

    public GlobalExceptionHandler(Tracer tracer) {
        this.tracer = tracer;
    }


    // =========================================================
    // 1. INVALID OR MALFORMED REQUEST BODY
    // =========================================================

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableRequestBody(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {

        ProblemDetail problem =
                ProblemDetail.forStatus(
                        HttpStatus.BAD_REQUEST
                );

        problem.setType(
                URI.create(
                        "urn:problem:invalid-request-body"
                )
        );

        problem.setTitle(
                "Invalid Request Body"
        );

        problem.setDetail(
                "The request body is invalid or malformed"
        );

        problem.setInstance(
                URI.create(request.getRequestURI())
        );

        addTraceId(problem);

        return ResponseEntity
                .badRequest()
                .body(problem);
    }


    // =========================================================
    // 2. DOMAIN / BUSINESS EXCEPTION
    // =========================================================

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetail> handleDomainException(
            DomainException exception,
            HttpServletRequest request
    ) {

        int status =
                exception.getCode() != null
                        ? exception.getCode()
                        : HttpStatus.INTERNAL_SERVER_ERROR.value();

        HttpStatus httpStatus =
                HttpStatus.resolve(status);

        ProblemDetail problem =
                ProblemDetail.forStatus(status);

        problem.setType(
                URI.create(
                        "urn:problem:domain-error"
                )
        );

        problem.setTitle(
                httpStatus != null
                        ? httpStatus.getReasonPhrase()
                        : "Domain Error"
        );

        problem.setDetail(
                exception.getMessage() != null
                        ? exception.getMessage()
                        : "A domain error occurred"
        );

        problem.setInstance(
                URI.create(request.getRequestURI())
        );

        addTraceId(problem);

        return ResponseEntity
                .status(status)
                .body(problem);
    }


    // =========================================================
    // 3. REQUEST VALIDATION ERROR
    // @NotBlank, @Size, @Email, @Pattern, ...
    // =========================================================

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {

        ProblemDetail problem =
                ProblemDetail.forStatus(
                        HttpStatus.BAD_REQUEST
                );

        problem.setType(
                URI.create(
                        "urn:problem:validation-error"
                )
        );

        problem.setTitle(
                "Validation Failed"
        );

        problem.setDetail(
                "The request data is invalid"
        );

        problem.setInstance(
                URI.create(request.getRequestURI())
        );

        /*
         * Keeps only one validation error per field.
         *
         * Example:
         *
         * email -> Invalid email address!
         * name  -> cannot be blank!
         */
        Map<String, String> uniqueErrors =
                new LinkedHashMap<>();

        for (
                FieldError error
                : exception.getBindingResult().getFieldErrors()
        ) {

            String enumKey =
                    error.getDefaultMessage();

            String message =
                    getValidationMessage(enumKey);

            uniqueErrors.putIfAbsent(
                    error.getField(),
                    message
            );
        }

        List<ValidationError> errors =
                uniqueErrors
                        .entrySet()
                        .stream()
                        .map(entry ->
                                new ValidationError(
                                        entry.getKey(),
                                        entry.getValue()
                                )
                        )
                        .toList();

        problem.setProperty(
                "errors",
                errors
        );

        addTraceId(problem);

        return ResponseEntity
                .badRequest()
                .body(problem);
    }


    // =========================================================
    // 4. HTTP METHOD NOT SUPPORTED
    // Example:
    // Endpoint supports GET but client sends POST
    // =========================================================

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request
    ) {

        ProblemDetail problem =
                ProblemDetail.forStatus(
                        HttpStatus.METHOD_NOT_ALLOWED
                );

        problem.setType(
                URI.create(
                        "urn:problem:method-not-allowed"
                )
        );

        problem.setTitle(
                "Method Not Allowed"
        );

        problem.setDetail(
                "The HTTP method is not supported for this endpoint"
        );

        problem.setInstance(
                URI.create(request.getRequestURI())
        );

        addTraceId(problem);

        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(problem);
    }


    // =========================================================
    // 5. INVALID PARAMETER TYPE
    // Example:
    // UUID id expected but client sends "abc"
    // =========================================================

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request
    ) {

        ProblemDetail problem =
                ProblemDetail.forStatus(
                        HttpStatus.BAD_REQUEST
                );

        problem.setType(
                URI.create(
                        "urn:problem:invalid-parameter"
                )
        );

        problem.setTitle(
                "Invalid Parameter"
        );

        if (exception.getRequiredType() == UUID.class) {

            problem.setDetail(
                    "Value '" + exception.getValue()
                            + "' is not a valid UUID for parameter '"
                            + exception.getName() + "'"
            );

        } else {

            problem.setDetail(
                    "Value '" + exception.getValue()
                            + "' is invalid for parameter '"
                            + exception.getName() + "'"
            );
        }

        problem.setInstance(
                URI.create(request.getRequestURI())
        );

        addTraceId(problem);

        return ResponseEntity
                .badRequest()
                .body(problem);
    }


    // =========================================================
    // 6. UNEXPECTED / UNHANDLED ERROR
    // =========================================================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {

        String traceId =
                getTraceId();

        /*
         * The real exception and stack trace are logged
         * on the server only.
         *
         * They are never exposed to the client.
         */
        log.error(
                "Unexpected exception. traceId={}",
                traceId,
                exception
        );

        ProblemDetail problem =
                ProblemDetail.forStatus(
                        HttpStatus.INTERNAL_SERVER_ERROR
                );

        problem.setType(
                URI.create(
                        "urn:problem:internal-server-error"
                )
        );

        problem.setTitle(
                "Internal Server Error"
        );

        problem.setDetail(
                "An unexpected error occurred"
        );

        problem.setInstance(
                URI.create(request.getRequestURI())
        );

        if (traceId != null) {

            problem.setProperty(
                    "traceId",
                    traceId
            );
        }

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(problem);
    }


    // =========================================================
    // VALIDATION MESSAGE RESOLUTION
    // =========================================================

    private String getValidationMessage(
            String enumKey
    ) {

        if (
                enumKey == null
                        || enumKey.isBlank()
        ) {
            return "Invalid request data";
        }

        try {

            ErrorCode errorCode =
                    ErrorCode.valueOf(enumKey);

            if (errorCode.getMessage() != null) {
                return errorCode.getMessage();
            }

            return enumKey;

        } catch (IllegalArgumentException exception) {

            /*
             * If the annotation contains a normal message
             * instead of an ErrorCode enum name,
             * return the message directly.
             */
            return enumKey;
        }
    }


    // =========================================================
    // TRACE ID
    // =========================================================

    private void addTraceId(
            ProblemDetail problem
    ) {

        String traceId =
                getTraceId();

        if (traceId != null) {

            problem.setProperty(
                    "traceId",
                    traceId
            );
        }
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


    // =========================================================
    // VALIDATION ERROR RESPONSE
    // =========================================================

    private record ValidationError(
            String field,
            String message
    ) {
    }
}