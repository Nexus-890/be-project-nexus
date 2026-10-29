package com.group5.catalogservice.product;

import com.group5.catalogservice.product.dto.response.ImageResponse;
import com.group5.common_security.CurrentUser;
import com.group5.common_core.exception.DomainException;
import com.group5.common_web.exception.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products/{id}/images")
public class ProductImageController {
    private final ProductImageService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SELLER') and hasAuthority('PRODUCT.UPDATE')")
    public ImageResponse upload(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
                                CurrentUser user) {
        return service.upload(id, file, user);
    }

    // Multipart parsing can fail before MVC has selected this controller.
    @RestControllerAdvice
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @RequiredArgsConstructor
    public static class MultipartErrors {
        private final GlobalExceptionHandler errors;

        @ExceptionHandler(MaxUploadSizeExceededException.class)
        public ResponseEntity<ProblemDetail> tooLarge(MaxUploadSizeExceededException exception,
                                                     HttpServletRequest request) {
            return errors.handleDomainException(new DomainException(413,
                    "Image exceeds the maximum file size"), request);
        }

        @ExceptionHandler(MissingServletRequestPartException.class)
        public ResponseEntity<ProblemDetail> missingFile(MissingServletRequestPartException exception,
                                                        HttpServletRequest request) {
            return errors.handleDomainException(new DomainException(400,
                    "An image file is required"), request);
        }
    }
}
