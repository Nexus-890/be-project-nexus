package com.group5.catalogservice.product;

import com.group5.catalogservice.product.dto.response.ImageResponse;
import com.group5.catalogservice.product.dto.request.ImageOrderRequest;
import jakarta.validation.Valid;
import com.group5.catalogservice.shared.exception.CatalogErrorCode;
import com.group5.common_core.model.ApiResponse;
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
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products/{id}/images")
public class ProductImageController {
    private final ProductImageService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SELLER') and hasAuthority('PRODUCT.UPDATE')")
    public ApiResponse<ImageResponse> upload(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
                                CurrentUser user) {
        return ApiResponse.of(service.upload(id, file, user));
    }

    @DeleteMapping("/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('SELLER') and hasAuthority('PRODUCT.UPDATE')")
    public void delete(@PathVariable UUID id, @PathVariable UUID imageId, CurrentUser user) {
        service.delete(id, imageId, user);
    }

    @PutMapping("/order")
    @PreAuthorize("hasRole('SELLER') and hasAuthority('PRODUCT.UPDATE')")
    public ApiResponse<List<ImageResponse>> reorder(@PathVariable UUID id,
                                                   @Valid @RequestBody ImageOrderRequest request,
                                                   CurrentUser user) {
        return ApiResponse.list(service.reorder(id, request.imageIds(), user));
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
            return errors.handleDomainException(new DomainException(CatalogErrorCode.IMAGE_TOO_LARGE), request);
        }

        @ExceptionHandler(MissingServletRequestPartException.class)
        public ResponseEntity<ProblemDetail> missingFile(MissingServletRequestPartException exception,
                                                        HttpServletRequest request) {
            return errors.handleDomainException(new DomainException(CatalogErrorCode.IMAGE_REQUIRED), request);
        }
    }
}
