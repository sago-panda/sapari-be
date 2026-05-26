package com.sapari.apiapp.controller.member;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sapari.apiapp.controller.member.dto.request.MemberMeUpdateRequest;
import com.sapari.apiapp.controller.member.dto.request.SocialSignupRequest;
import com.sapari.apiapp.controller.member.dto.response.DuplicateCheckResponse;
import com.sapari.apiapp.controller.member.dto.response.MemberMeResponse;
import com.sapari.apiapp.controller.member.dto.response.SocialLoginResponse;
import com.sapari.apiapp.controller.member.dto.response.SocialSignupResponse;
import com.sapari.apiapp.controller.member.dto.response.TokenReissueResponse;
import com.sapari.member.command.MemberLogoutCommand;
import com.sapari.member.domain.exception.MemberErrorCode;
import com.sapari.member.domain.exception.MemberException;
import com.sapari.member.port.MemberAuthFacade;
import com.sapari.member.result.MemberMeResult;
import com.sapari.member.result.MemberTokenReissueResult;
import com.sapari.member.result.SocialLoginTokenResult;
import com.sapari.member.result.SocialSignupResult;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
public class MemberAuthController {

    private static final String SIGNUP_SID_COOKIE_NAME = "signup_sid";
    private static final String TEMPORARY_LOGIN_CODE_COOKIE_NAME = "temporary_login_code";
    private static final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";
    private static final String BEARER_PREFIX = "Bearer ";

    private final MemberAuthFacade memberAuthFacade;

    @Value("${jwt.refresh-token-expiration-seconds}")
    private long refreshTokenExpirationSeconds;

    @PostMapping("/auth/members/signup/social")
    public ResponseEntity<SocialSignupResponse> completeSocialSignup(
            @CookieValue(name = SIGNUP_SID_COOKIE_NAME, required = false) String signupSid,
            @Valid @RequestBody SocialSignupRequest request
    ) {
        SocialSignupResult result = memberAuthFacade.completeSocialSignup(signupSid, request.toCommand());

        return ResponseEntity
                .ok()
                .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + result.accessToken())
                .header(HttpHeaders.SET_COOKIE, createRefreshTokenCookie(result.refreshToken()).toString())
                .header(HttpHeaders.SET_COOKIE, createDeleteCookie(SIGNUP_SID_COOKIE_NAME).toString())
                .body(SocialSignupResponse.from(result));
    }

    @GetMapping("/auth/members/signup/check-phone")
    public ResponseEntity<DuplicateCheckResponse> checkPhoneNumber(
            @RequestParam
            @Pattern(regexp = "^\\d{11}$", message = "전화번호는 숫자 11자리여야 합니다.")
            String phoneNumber
    ) {
        return ResponseEntity.ok(
                new DuplicateCheckResponse(memberAuthFacade.isPhoneNumberDuplicated(phoneNumber))
        );
    }

    @GetMapping("/auth/members/signup/check-email")
    public ResponseEntity<DuplicateCheckResponse> checkEmail(
            @RequestParam
            @NotBlank(message = "이메일은 필수입니다.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            String email
    ) {
        return ResponseEntity.ok(
                new DuplicateCheckResponse(memberAuthFacade.isEmailDuplicated(email))
        );
    }

    @PostMapping("/auth/members/login/social/code")
    public ResponseEntity<SocialLoginResponse> exchangeSocialLoginCode(
            @CookieValue(name = TEMPORARY_LOGIN_CODE_COOKIE_NAME, required = false) String temporaryLoginCode
    ) {
        SocialLoginTokenResult result = memberAuthFacade.exchangeTemporaryLoginCode(temporaryLoginCode);

        return ResponseEntity
                .ok()
                .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + result.accessToken())
                .header(HttpHeaders.SET_COOKIE, createRefreshTokenCookie(result.refreshToken()).toString())
                .header(HttpHeaders.SET_COOKIE, createDeleteCookie(TEMPORARY_LOGIN_CODE_COOKIE_NAME).toString())
                .body(SocialLoginResponse.from(result));
    }

    @PostMapping("/auth/token/reissue")
    public ResponseEntity<TokenReissueResponse> reissueAccessToken(
            @CookieValue(name = REFRESH_TOKEN_COOKIE_NAME, required = false) String refreshToken
    ) {
        MemberTokenReissueResult result = memberAuthFacade.reissueAccessToken(refreshToken);

        return ResponseEntity
                .ok()
                .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + result.accessToken())
                .body(TokenReissueResponse.from(result));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal(expression = "user.userId") UUID userId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        memberAuthFacade.logout(new MemberLogoutCommand(userId, resolveAccessToken(authorizationHeader)));

        return ResponseEntity
                .noContent()
                .header(HttpHeaders.SET_COOKIE, createDeleteCookie(REFRESH_TOKEN_COOKIE_NAME).toString())
                .build();
    }

    @GetMapping("/members/me")
    public ResponseEntity<MemberMeResponse> getMyInfo(
            @AuthenticationPrincipal(expression = "user.userId") UUID userId
    ) {
        MemberMeResult result = memberAuthFacade.getMyInfo(userId);

        return ResponseEntity.ok(MemberMeResponse.from(result));
    }

    @PutMapping("/members/me")
    public ResponseEntity<MemberMeResponse> updateMyInfo(
            @AuthenticationPrincipal(expression = "user.userId") UUID userId,
            @Valid @RequestBody MemberMeUpdateRequest request
    ) {
        MemberMeResult result = memberAuthFacade.updateMyInfo(request.toCommand(userId));

        return ResponseEntity.ok(MemberMeResponse.from(result));
    }

    private ResponseCookie createRefreshTokenCookie(String refreshToken) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, refreshToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(refreshTokenExpirationSeconds))
                .build();
    }

    private ResponseCookie createDeleteCookie(String cookieName) {
        return ResponseCookie.from(cookieName, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    private String resolveAccessToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new MemberException(MemberErrorCode.INVALID_ACCESS_TOKEN);
        }

        return authorizationHeader.substring(BEARER_PREFIX.length());
    }
}
