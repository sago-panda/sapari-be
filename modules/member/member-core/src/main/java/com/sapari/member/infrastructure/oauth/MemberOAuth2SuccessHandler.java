package com.sapari.member.infrastructure.oauth;

import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.sapari.member.command.MemberOAuthCommand;
import com.sapari.member.port.MemberOAuthUseCase;
import com.sapari.member.result.MemberOAuthResult;

@Component
@RequiredArgsConstructor
public class MemberOAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private static final String TEMPORARY_LOGIN_CODE_COOKIE_NAME = "temporary_login_code";
    private static final Duration TEMPORARY_LOGIN_CODE_COOKIE_MAX_AGE = Duration.ofMinutes(3);
    private static final String SIGNUP_SID_COOKIE_NAME = "signup_sid";
    private static final Duration SIGNUP_SID_COOKIE_MAX_AGE = Duration.ofMinutes(30);

    private final MemberOAuthUseCase memberOAuthUseCase;
    private final MemberOAuthRedirectProperties redirectProperties;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException, ServletException {
        MemberOAuth2User memberOAuth2User = getMemberOAuth2User(authentication);
        MemberOAuthResult result = memberOAuthUseCase.handleOAuthSuccess(toCommand(memberOAuth2User));

        switch (result.type()) {
            case LOGIN_SUCCESS -> redirectLoginSuccess(response, result);
            case SIGNUP_REQUIRED -> redirectSignup(response, result);
        }
    }

    private MemberOAuth2User getMemberOAuth2User(Authentication authentication) {
        Object principal = authentication.getPrincipal();

        if (principal instanceof MemberOAuth2User memberOAuth2User) {
            return memberOAuth2User;
        }

        throw new AuthenticationServiceException("구매자 OAuth2 인증 정보가 아닙니다.");
    }

    private MemberOAuthCommand toCommand(MemberOAuth2User user) {
        return new MemberOAuthCommand(
                user.getProvider().name(),
                user.getProviderId(),
                user.getProviderEmail(),
                user.getProfileName(),
                user.getNickname(),
                user.getPhoneNumber(),
                user.getProfileImageUrl(),
                user.getGender() == null ? null : user.getGender().name(),
                user.getBirthDate()
        );
    }

    private void redirectLoginSuccess(HttpServletResponse response, MemberOAuthResult result) throws IOException {
        ResponseCookie temporaryLoginCodeCookie = ResponseCookie.from(
                        TEMPORARY_LOGIN_CODE_COOKIE_NAME,
                        result.loginCode()
                )
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(TEMPORARY_LOGIN_CODE_COOKIE_MAX_AGE)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, temporaryLoginCodeCookie.toString());
        response.sendRedirect(redirectProperties.getLoginSuccessUrl());
    }

    private void redirectSignup(HttpServletResponse response, MemberOAuthResult result) throws IOException {
        ResponseCookie signupSidCookie = ResponseCookie.from(
                        SIGNUP_SID_COOKIE_NAME,
                        URLEncoder.encode(result.signupSid(), StandardCharsets.UTF_8)
                )
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(SIGNUP_SID_COOKIE_MAX_AGE)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, signupSidCookie.toString());
        response.sendRedirect(redirectProperties.getSignupUrl());
    }
}
