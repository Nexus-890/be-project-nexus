package com.group5.userservice.iam.dto;

import com.group5.userservice.iam.enums.UserStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public record UserListRequest(
        String q,
        UserStatus status,
        String role,
        @Min(0) Integer page,
        @Min(1) @Max(100) Integer size,
        @Pattern(regexp = "fullName|email|createdAt") String sort,
        @Pattern(regexp = "asc|desc") String direction
) { //SPRING AUTO COLLECT PARAM ON URL PUT INTO THIS RECORD
    //NẾU THIẾU THÌ GÁN DEFAULT

    public UserListRequest {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
        sort = sort == null ? "fullName" : sort;
        direction = direction == null ? "asc" : direction;
    }

    public Pageable pageable() {
        Sort order = Sort.by(Sort.Direction.fromString(direction), sort).and(Sort.by("id"));
        return PageRequest.of(page, size, order);
    }
}
