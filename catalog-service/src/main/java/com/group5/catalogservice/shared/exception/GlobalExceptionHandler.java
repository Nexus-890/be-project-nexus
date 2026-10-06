package com.group5.catalogservice.shared.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(
            IllegalArgumentException e) {

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("type", "about:blank");
        response.put("title", "Bad Request");
        response.put("status", HttpStatus.BAD_REQUEST.value());
        response.put("detail", e.getMessage());

        return ResponseEntity
                .badRequest()
                .body(response);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            NoSuchElementException e) {

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("type", "about:blank");
        response.put("title", "Not Found");
        response.put("status", HttpStatus.NOT_FOUND.value());
        response.put("detail", e.getMessage());

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(response);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(
            DataIntegrityViolationException e) {


        Map<String, Object> response = new LinkedHashMap<>();

        response.put("type", "about:blank");
        response.put("title", "Conflict");
        response.put("status", HttpStatus.CONFLICT.value());
        response.put("detail", e.getMessage());

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(response);
    }
}
