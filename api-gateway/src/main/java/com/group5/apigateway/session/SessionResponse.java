package com.group5.apigateway.session;

// JS chỉ nhận token sống ngắn, refresh token ở lại trong cookie
public record SessionResponse(String accessToken, String idToken, long expiresIn) {
}
