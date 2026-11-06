package com.group5.apigateway.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// phần của token response mà gateway dùng. Không được log object này vì có token
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeycloakTokens(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("id_token") String idToken,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("refresh_expires_in") long refreshExpiresIn) {
}
