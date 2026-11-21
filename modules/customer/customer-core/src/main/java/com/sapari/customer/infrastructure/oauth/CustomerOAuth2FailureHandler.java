package com.sapari.customer.infrastructure.oauth;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 소셜 로그인 실패(동의 취소, provider 오류, 인가 요청 쿠키 만료·위조 등)를 프론트 오류 경로로 돌려보낸다.
 * provider 가 준 오류 설명은 사용자 정보나 내부 사정을 담을 수 있어 URL 에 싣지 않고 고정 코드만 넘긴다.
 */
@Slf4j
@Component
public class CustomerOAuth2FailureHandler implements AuthenticationFailureHandler {

    static final String ERROR_PARAMETER = "error";
    static final String ACCESS_DENIED = "access_denied";
    static final String LOGIN_FAILED = "login_failed";

    private final String failureUrl;

    public CustomerOAuth2FailureHandler(CustomerOAuthRedirectProperties redirectProperties) {
        // 비어 있으면 동의 취소까지 모든 실패가 500 이 되거나 api 호스트의 상대 경로로 가므로 기동 때 막는다.
        this.failureUrl = requireAbsoluteHttpUrl(redirectProperties.getFailureUrl());
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {
        String errorCode = toErrorCode(exception);
        // 운영 추적용으로 OAuth2 오류 코드까지만 남긴다. 설명(description)은 provider 원문이라 남기지 않는다.
        log.info("소셜 로그인 실패. errorCode={}, oauth2ErrorCode={}, exception={}",
                errorCode, oauth2ErrorCode(exception), exception.getClass().getSimpleName());

        String redirectUrl = UriComponentsBuilder.fromUriString(failureUrl)
                .queryParam(ERROR_PARAMETER, errorCode)
                .build()
                .toUriString();
        response.sendRedirect(redirectUrl);
    }

    private static String requireAbsoluteHttpUrl(String url) {
        try {
            URI uri = url == null ? null : URI.create(url.strip());
            if (uri != null && uri.getHost() != null
                    && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
                return uri.toString();
            }
        } catch (IllegalArgumentException ignored) {
            // 아래에서 같은 메시지로 기동을 막는다.
        }
        throw new IllegalArgumentException("customer.oauth2.redirect.failure-url 은 http(s) 절대 URL 이어야 합니다.");
    }

    private String toErrorCode(AuthenticationException exception) {
        return OAuth2ErrorCodes.ACCESS_DENIED.equals(oauth2ErrorCode(exception)) ? ACCESS_DENIED : LOGIN_FAILED;
    }

    private String oauth2ErrorCode(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauth2Exception) {
            return oauth2Exception.getError().getErrorCode();
        }
        return null;
    }
}
