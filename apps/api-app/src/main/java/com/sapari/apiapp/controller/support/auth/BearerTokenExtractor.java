package com.sapari.apiapp.controller.support.auth;

import java.util.Optional;

public final class BearerTokenExtractor {

    private static final String BEARER_PREFIX = "Bearer ";

    private BearerTokenExtractor() {
    }

    public static Optional<String> extract(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }

        return Optional.of(authorizationHeader.substring(BEARER_PREFIX.length()));
    }

    public static String toAuthorizationHeader(String accessToken) {
        return BEARER_PREFIX + accessToken;
    }
}
