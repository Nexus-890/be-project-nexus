package com.group5.userservice.avatar;

import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import com.group5.common_web.exception.GlobalExceptionHandler;
import com.group5.userservice.iam.dto.UserResponse;
import com.group5.userservice.shared.exception.UserErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me/avatar")
public class AvatarController {
    private final AvatarService avatars;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('PROFILE.UPDATE')")
    public ApiResponse<UserResponse> upload(CurrentUser user, @RequestPart("file") MultipartFile file) {
        return ApiResponse.of(avatars.replace(user.id(), file));
    }

    @DeleteMapping
    @PreAuthorize("hasAuthority('PROFILE.UPDATE')")
    public ApiResponse<UserResponse> remove(CurrentUser user) {
        return ApiResponse.of(avatars.remove(user.id()));
    }

    @RestControllerAdvice(assignableTypes = AvatarController.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @RequiredArgsConstructor
    public static class MultipartErrors {
        private final GlobalExceptionHandler errors;

        @ExceptionHandler(MissingServletRequestPartException.class)
        public ResponseEntity<ProblemDetail> missingFile(MissingServletRequestPartException exception,
                                                         HttpServletRequest request) {
            return errors.handleDomainException(new DomainException(UserErrorCode.USER_AVATAR_REQUIRED), request);
        }
    }

    @RestControllerAdvice
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @RequiredArgsConstructor
    public static class UploadLimit {
        private final GlobalExceptionHandler errors;

        @ExceptionHandler(MaxUploadSizeExceededException.class)
        public ResponseEntity<ProblemDetail> tooLarge(MaxUploadSizeExceededException exception,
                                                      HttpServletRequest request) {
            return errors.handleDomainException(new DomainException(UserErrorCode.USER_AVATAR_TOO_LARGE), request);
        }
    }
}
