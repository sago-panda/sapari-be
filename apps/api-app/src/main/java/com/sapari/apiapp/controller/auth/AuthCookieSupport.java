package com.sapari.apiapp.controller.auth;

import java.time.Duration;

import org.springframework.http.ResponseCookie;

public final class AuthCookieSupport {

    public static final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";

    private AuthCookieSupport() {
    }

    public static ResponseCookie createRefreshTokenCookie(
            String refreshToken,
            long refreshTokenExpirationSeconds
    ) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, refreshToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(refreshTokenExpirationSeconds))
                .build();
    }

    public static ResponseCookie createDeleteCookie(String cookieName) {
        return ResponseCookie.from(cookieName, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }
}
