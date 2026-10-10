package com.group5.userservice.iam.dto;

//Record nên java tự sinh constructor rồi !
public record LoginResponse(String accessToken, long expiresIn, String tokenType) {

    public static LoginResponse bearer(String accessToken, long expiresIn) {
        return new LoginResponse(accessToken, expiresIn, "Bearer");
    }
}