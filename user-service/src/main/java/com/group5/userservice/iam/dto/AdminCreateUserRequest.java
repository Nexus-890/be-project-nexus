package com.group5.userservice.iam.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Thông tin tạo tài khoản quản trị sử dụng cùng validation với tự đăng ký. */
public record AdminCreateUserRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Pattern(regexp = "^\\+?[0-9]{7,15}$") String phone,
        @NotBlank @Size(min = 8) String password,
        @NotBlank @Size(max = 150) String fullName,
        @NotBlank @Size(max = 255) String identityNumber,
        @NotBlank @Pattern(regexp = "MALE|FEMALE|OTHER") String gender,
        @NotBlank String address,
        @NotBlank @RegisterRequest.DateOfBirth String dateOfBirth,
        @NotEmpty List<@NotBlank String> roleCodes) {
    /** Dùng bộ chuẩn hóa giống tự đăng ký trước khi controller kiểm tra validation. */
    public AdminCreateUserRequest {
        RegisterRequest normalized = new RegisterRequest(email, phone, password, fullName, identityNumber, gender, address, dateOfBirth);
        email = normalized.email(); phone = normalized.phone(); fullName = normalized.fullName();
        identityNumber = normalized.identityNumber(); gender = normalized.gender(); address = normalized.address();
        dateOfBirth = normalized.dateOfBirth();
    }

    /** Tái sử dụng dữ liệu đăng ký đã chuẩn hóa khi tạo profile. */
    public RegisterRequest profile() {
        return new RegisterRequest(email, phone, password, fullName, identityNumber, gender, address, dateOfBirth);
    }
    /** Che toàn bộ dữ liệu cá nhân và mật khẩu khi đối tượng được ghi log. */
    @Override public String toString() { return "AdminCreateUserRequest[redacted]"; }
}
