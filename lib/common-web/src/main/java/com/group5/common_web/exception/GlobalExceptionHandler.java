package com.group5.common_web.exception;
import com.group5.common_core.constant.ErrorCode;
import com.group5.common_core.exception.DomainException;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import jakarta.servlet.http.HttpServletRequest;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import org.springframework.validation.FieldError;

import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {


    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Tracer tracer;


    public GlobalExceptionHandler(Tracer tracer) {
        this.tracer = tracer;
    }

    // 1. JSON REQUEST BODY KHÔNG HỢP LỆ

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
                "Dữ liệu yêu cầu không hợp lệ"
        );

        problem.setInstance(
                URI.create(request.getRequestURI())
        );

        addTraceId(problem);

        return ResponseEntity
                .badRequest()
                .body(problem);
    }

    // 2. DOMAIN EXCEPTION

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

        /*
         * Ví dụ status = 404
         *
         * title = "Not Found"
         *
         * status = 400
         *
         * title = "Bad Request"
         */
        problem.setTitle(
                httpStatus != null
                        ? httpStatus.getReasonPhrase()
                        : "Domain Error"
        );

        problem.setDetail(
                exception.getMessage() != null
                        ? exception.getMessage()
                        : "Đã xảy ra lỗi"
        );

        problem.setInstance(
                URI.create(request.getRequestURI())
        );

        addTraceId(problem);

        return ResponseEntity
                .status(status)
                .body(problem);
    }

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
                "Dữ liệu không hợp lệ"
        );

        problem.setInstance(
                URI.create(request.getRequestURI())
        );

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

            /*
             * Nếu cùng một field có nhiều lỗi:
             *
             * chỉ lấy lỗi đầu tiên.
             */
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

    // 4. TẤT CẢ LỖI KHÔNG DỰ ĐOÁN ĐƯỢC

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {

        String traceId =
                getTraceId();
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
                "Đã xảy ra lỗi hệ thống"
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
                .status(
                        HttpStatus.INTERNAL_SERVER_ERROR
                )
                .body(problem);
    }

    // VALIDATION MESSAGE

    private String getValidationMessage(
            String enumKey
    ) {

        if (
                enumKey == null
                        || enumKey.isBlank()
        ) {
            return "Dữ liệu không hợp lệ";
        }


        try {

            ErrorCode errorCode =
                    ErrorCode.valueOf(enumKey);


            if (errorCode.getMessage() != null) {
                return errorCode.getMessage();
            }


            return enumKey;

        } catch (IllegalArgumentException exception) {

            return enumKey;
        }
    }

    // TRACE ID

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


    // VALIDATION ERROR RESPONSE

    private record ValidationError(
            String field,
            String message
    ) {
    }
}
