package com.sapari.apiapp.controller.member;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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

import com.sapari.apiapp.controller.member.dto.request.MemberNicknameUpdateRequest;
import com.sapari.apiapp.controller.member.dto.request.SocialSignupRequest;
import com.sapari.apiapp.controller.member.dto.response.DuplicateCheckResponse;
import com.sapari.apiapp.controller.member.dto.response.MemberMeResponse;
import com.sapari.apiapp.controller.member.dto.response.SocialSignupInfoResponse;
import com.sapari.apiapp.controller.member.dto.response.SocialLoginResponse;
import com.sapari.apiapp.controller.member.dto.response.SocialSignupResponse;
import com.sapari.apiapp.controller.member.dto.response.TokenReissueResponse;
import com.sapari.member.command.MemberLogoutCommand;
import com.sapari.member.domain.exception.MemberErrorCode;
import com.sapari.member.domain.exception.MemberException;
import com.sapari.member.port.MemberAuthUseCase;
import com.sapari.member.result.MemberMeResult;
import com.sapari.member.result.MemberNicknameUpdateResult;
import com.sapari.member.result.MemberTokenReissueResult;
import com.sapari.member.result.SocialSignupInfoResult;
import com.sapari.member.result.SocialLoginTokenResult;
import com.sapari.member.result.SocialSignupResult;

@RestController
@RequestMapping("/api/v1/members/auth")
@RequiredArgsConstructor
@Validated
public class MemberAuthController {

    private static final String SIGNUP_SID_COOKIE_NAME = "signup_sid";
    private static final String TEMPORARY_LOGIN_CODE_COOKIE_NAME = "temporary_login_code";
    private static final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";
    private static final String BEARER_PREFIX = "Bearer ";

    private final MemberAuthUseCase memberAuthUseCase;

    @Value("${jwt.refresh-token-expiration-seconds}")
    private long refreshTokenExpirationSeconds;

    @PostMapping("/signup/social")
    public ResponseEntity<SocialSignupResponse> completeSocialSignup(
            @CookieValue(name = SIGNUP_SID_COOKIE_NAME, required = false) String signupSid,
            @Valid @RequestBody SocialSignupRequest request
    ) {
        SocialSignupResult result = memberAuthUseCase.completeSocialSignup(signupSid, request.toCommand());

        return ResponseEntity
                .ok()
                .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + result.accessToken())
                .header(HttpHeaders.SET_COOKIE, createRefreshTokenCookie(result.refreshToken()).toString())
                .header(HttpHeaders.SET_COOKIE, createDeleteCookie(SIGNUP_SID_COOKIE_NAME).toString())
                .body(SocialSignupResponse.from(result));
    }

    @GetMapping("/signup/social-info")
    public ResponseEntity<SocialSignupInfoResponse> getSocialSignupInfo(
            @CookieValue(name = SIGNUP_SID_COOKIE_NAME, required = false) String signupSid
    ) {
        SocialSignupInfoResult result = memberAuthUseCase.getSocialSignupInfo(signupSid);

        return ResponseEntity
                .ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(SocialSignupInfoResponse.from(result));
    }

    @GetMapping("/signup/check-phone")
    public ResponseEntity<DuplicateCheckResponse> checkPhoneNumber(
            @RequestParam
            @Pattern(regexp = "^0\\d{8,10}$", message = "전화번호 형식이 올바르지 않습니다.")
            String phoneNumber
    ) {
        return ResponseEntity.ok(
                new DuplicateCheckResponse(memberAuthUseCase.isPhoneNumberDuplicated(phoneNumber))
        );
    }

    @GetMapping("/signup/check-email")
    public ResponseEntity<DuplicateCheckResponse> checkEmail(
            @RequestParam
            @NotBlank(message = "이메일은 필수입니다.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            String email
    ) {
        return ResponseEntity.ok(
                new DuplicateCheckResponse(memberAuthUseCase.isEmailDuplicated(email))
        );
    }

    @GetMapping("/signup/check-nickname")
    public ResponseEntity<DuplicateCheckResponse> checkNickname(
            @RequestParam
            @NotBlank(message = "닉네임은 필수입니다.")
            @Size(max = 10, message = "닉네임은 10자 이하여야 합니다.")
            String nickname
    ) {
        return ResponseEntity.ok(
                new DuplicateCheckResponse(memberAuthUseCase.isNicknameDuplicated(nickname))
        );
    }

    @PostMapping("/login/social/code")
    public ResponseEntity<SocialLoginResponse> exchangeSocialLoginCode(
            @CookieValue(name = TEMPORARY_LOGIN_CODE_COOKIE_NAME, required = false) String temporaryLoginCode
    ) {
        SocialLoginTokenResult result = memberAuthUseCase.exchangeTemporaryLoginCode(temporaryLoginCode);

        return ResponseEntity
                .ok()
                .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + result.accessToken())
                .header(HttpHeaders.SET_COOKIE, createRefreshTokenCookie(result.refreshToken()).toString())
                .header(HttpHeaders.SET_COOKIE, createDeleteCookie(TEMPORARY_LOGIN_CODE_COOKIE_NAME).toString())
                .body(SocialLoginResponse.from(result));
    }

    @PostMapping("/token/reissue")
    public ResponseEntity<TokenReissueResponse> reissueAccessToken(
            @CookieValue(name = REFRESH_TOKEN_COOKIE_NAME, required = false) String refreshToken
    ) {
        MemberTokenReissueResult result = memberAuthUseCase.reissueAccessToken(refreshToken);

        return ResponseEntity
                .ok()
                .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + result.accessToken())
                .body(TokenReissueResponse.from(result));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal(expression = "user.userId") UUID userId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        memberAuthUseCase.logout(new MemberLogoutCommand(userId, resolveAccessToken(authorizationHeader)));

        return ResponseEntity
                .noContent()
                .header(HttpHeaders.SET_COOKIE, createDeleteCookie(REFRESH_TOKEN_COOKIE_NAME).toString())
                .build();
    }

    @GetMapping("/me")
    public ResponseEntity<MemberMeResponse> getMyInfo(
            @AuthenticationPrincipal(expression = "user.userId") UUID userId
    ) {
        MemberMeResult result = memberAuthUseCase.getMyInfo(userId);

        return ResponseEntity.ok(MemberMeResponse.from(result));
    }

    @GetMapping("/me/check-nickname")
    public ResponseEntity<DuplicateCheckResponse> checkMyNickname(
            @AuthenticationPrincipal(expression = "user.userId") UUID userId,
            @RequestParam
            @NotBlank(message = "닉네임은 필수입니다.")
            @Size(max = 10, message = "닉네임은 10자 이하여야 합니다.")
            String nickname
    ) {
        return ResponseEntity.ok(
                new DuplicateCheckResponse(memberAuthUseCase.isMyNicknameDuplicated(userId, nickname))
        );
    }

    @PutMapping("/me/nickname")
    public ResponseEntity<MemberMeResponse> updateNickname(
            @AuthenticationPrincipal(expression = "user.userId") UUID userId,
            @Valid @RequestBody MemberNicknameUpdateRequest request
    ) {
        MemberNicknameUpdateResult result = memberAuthUseCase.updateNickname(request.toCommand(userId));

        return ResponseEntity
                .ok()
                .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + result.accessToken())
                .body(MemberMeResponse.from(result.member()));
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
