package com.sapari.apiapp.controller.member;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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

import com.sapari.apiapp.controller.auth.AuthCookieSupport;
import com.sapari.apiapp.controller.auth.BearerTokenExtractor;
import com.sapari.apiapp.controller.auth.dto.response.DuplicateCheckResponse;
import com.sapari.apiapp.controller.member.dto.request.MemberNicknameUpdateRequest;
import com.sapari.apiapp.controller.member.dto.request.SocialSignupRequest;
import com.sapari.apiapp.controller.member.dto.response.MemberMeResponse;
import com.sapari.apiapp.controller.member.dto.response.SocialSignupInfoResponse;
import com.sapari.apiapp.controller.member.dto.response.SocialLoginResponse;
import com.sapari.apiapp.controller.member.dto.response.SocialSignupResponse;
import com.sapari.apiapp.controller.member.dto.response.TokenReissueResponse;
import com.sapari.common.web.security.CurrentUserId;
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

    private final MemberAuthUseCase memberAuthUseCase;

    @Value("${jwt.refresh-token-expiration-seconds}")
    private long refreshTokenExpirationSeconds;

    @PostMapping("/signup/social")
    public ResponseEntity<SocialSignupResponse> completeSocialSignup(
            @CookieValue(name = SIGNUP_SID_COOKIE_NAME) String signupSid,
            @Valid @RequestBody SocialSignupRequest request
    ) {
        SocialSignupResult result = memberAuthUseCase.completeSocialSignup(signupSid, request.toCommand());

        return ResponseEntity
                .ok()
                .header(HttpHeaders.AUTHORIZATION, BearerTokenExtractor.toAuthorizationHeader(result.accessToken()))
                .header(HttpHeaders.SET_COOKIE, AuthCookieSupport.createRefreshTokenCookie(
                        result.refreshToken(),
                        refreshTokenExpirationSeconds
                ).toString())
                .header(HttpHeaders.SET_COOKIE, AuthCookieSupport.createExpiredCookie(SIGNUP_SID_COOKIE_NAME).toString())
                .body(SocialSignupResponse.from(result));
    }

    @GetMapping("/signup/social-info")
    public ResponseEntity<SocialSignupInfoResponse> getSocialSignupInfo(
            @CookieValue(name = SIGNUP_SID_COOKIE_NAME) String signupSid
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
            @Pattern(regexp = "^010\\d{8}$", message = "전화번호는 010으로 시작하는 숫자 11자리여야 합니다.")
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

    @GetMapping("/check-nickname")
    public ResponseEntity<DuplicateCheckResponse> checkNickname(
            @RequestParam
            @NotBlank(message = "닉네임은 필수입니다.")
            @Pattern(
                    regexp = "^[가-힣A-Za-z0-9]{2,10}$",
                    message = "닉네임은 2~10자의 한글, 영문, 숫자만 사용할 수 있습니다."
            )
            String nickname
    ) {
        return ResponseEntity.ok(
                new DuplicateCheckResponse(memberAuthUseCase.isNicknameDuplicated(nickname))
        );
    }

    @PostMapping("/login/social/code")
    public ResponseEntity<SocialLoginResponse> exchangeSocialLoginCode(
            @CookieValue(name = TEMPORARY_LOGIN_CODE_COOKIE_NAME) String temporaryLoginCode
    ) {
        SocialLoginTokenResult result = memberAuthUseCase.exchangeTemporaryLoginCode(temporaryLoginCode);

        return ResponseEntity
                .ok()
                .header(HttpHeaders.AUTHORIZATION, BearerTokenExtractor.toAuthorizationHeader(result.accessToken()))
                .header(HttpHeaders.SET_COOKIE, AuthCookieSupport.createRefreshTokenCookie(
                        result.refreshToken(),
                        refreshTokenExpirationSeconds
                ).toString())
                .header(HttpHeaders.SET_COOKIE, AuthCookieSupport
                        .createExpiredCookie(TEMPORARY_LOGIN_CODE_COOKIE_NAME)
                        .toString())
                .body(SocialLoginResponse.from(result));
    }

    @PostMapping("/token/reissue")
    public ResponseEntity<TokenReissueResponse> reissueAccessToken(
            @CookieValue(name = AuthCookieSupport.REFRESH_TOKEN_COOKIE_NAME) String refreshToken
    ) {
        MemberTokenReissueResult result = memberAuthUseCase.reissueAccessToken(refreshToken);

        return ResponseEntity
                .ok()
                .header(HttpHeaders.AUTHORIZATION, BearerTokenExtractor.toAuthorizationHeader(result.accessToken()))
                .body(TokenReissueResponse.from(result));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CurrentUserId UUID userId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        memberAuthUseCase.logout(new MemberLogoutCommand(userId, resolveAccessToken(authorizationHeader)));

        return ResponseEntity
                .noContent()
                .header(HttpHeaders.SET_COOKIE, AuthCookieSupport
                        .createExpiredCookie(AuthCookieSupport.REFRESH_TOKEN_COOKIE_NAME)
                        .toString())
                .build();
    }

    @GetMapping("/me")
    public ResponseEntity<MemberMeResponse> getMyInfo(
            @CurrentUserId UUID userId
    ) {
        MemberMeResult result = memberAuthUseCase.getMyInfo(userId);

        return ResponseEntity.ok(MemberMeResponse.from(result));
    }

    @PutMapping("/me/nickname")
    public ResponseEntity<MemberMeResponse> updateNickname(
            @CurrentUserId UUID userId,
            @Valid @RequestBody MemberNicknameUpdateRequest request
    ) {
        MemberNicknameUpdateResult result = memberAuthUseCase.updateNickname(request.toCommand(userId));

        return ResponseEntity
                .ok()
                .header(HttpHeaders.AUTHORIZATION, BearerTokenExtractor.toAuthorizationHeader(result.accessToken()))
                .body(MemberMeResponse.from(result.member()));
    }

    private String resolveAccessToken(String authorizationHeader) {
        return BearerTokenExtractor.extract(authorizationHeader)
                .orElseThrow(() -> new MemberException(MemberErrorCode.INVALID_ACCESS_TOKEN));
    }
}
