package com.sapari.apiapp.controller.customer;

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
import com.sapari.apiapp.controller.customer.dto.request.CustomerNicknameUpdateRequest;
import com.sapari.apiapp.controller.customer.dto.request.SocialSignupRequest;
import com.sapari.apiapp.controller.customer.dto.response.CustomerMeResponse;
import com.sapari.apiapp.controller.customer.dto.response.SocialSignupInfoResponse;
import com.sapari.apiapp.controller.customer.dto.response.SocialLoginResponse;
import com.sapari.apiapp.controller.customer.dto.response.SocialSignupResponse;
import com.sapari.apiapp.controller.customer.dto.response.TokenReissueResponse;
import com.sapari.common.web.security.CurrentUserId;
import com.sapari.customer.command.CustomerLogoutCommand;
import com.sapari.customer.domain.exception.CustomerErrorCode;
import com.sapari.customer.domain.exception.CustomerException;
import com.sapari.customer.port.CustomerAuthUseCase;
import com.sapari.customer.view.CustomerMeView;
import com.sapari.customer.view.CustomerNicknameUpdateResult;
import com.sapari.customer.view.CustomerTokenReissueResult;
import com.sapari.customer.view.SocialSignupInfoView;
import com.sapari.customer.view.SocialLoginTokenResult;
import com.sapari.customer.view.SocialSignupResult;

@RestController
@RequestMapping("/api/v1/customers/auth")
@RequiredArgsConstructor
@Validated
public class CustomerAuthController {

    private static final String SIGNUP_SID_COOKIE_NAME = "signup_sid";
    private static final String TEMPORARY_LOGIN_CODE_COOKIE_NAME = "temporary_login_code";

    private final CustomerAuthUseCase customerAuthUseCase;

    @Value("${jwt.refresh-token-expiration-seconds}")
    private long refreshTokenExpirationSeconds;

    @PostMapping("/signup/social")
    public ResponseEntity<SocialSignupResponse> completeSocialSignup(
            @CookieValue(name = SIGNUP_SID_COOKIE_NAME) String signupSid,
            @Valid @RequestBody SocialSignupRequest request
    ) {
        SocialSignupResult result = customerAuthUseCase.completeSocialSignup(signupSid, request.toCommand());

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
        SocialSignupInfoView result = customerAuthUseCase.getSocialSignupInfo(signupSid);

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
                new DuplicateCheckResponse(customerAuthUseCase.isPhoneNumberDuplicated(phoneNumber))
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
                new DuplicateCheckResponse(customerAuthUseCase.isEmailDuplicated(email))
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
                new DuplicateCheckResponse(customerAuthUseCase.isNicknameDuplicated(nickname))
        );
    }

    @PostMapping("/login/social/code")
    public ResponseEntity<SocialLoginResponse> exchangeSocialLoginCode(
            @CookieValue(name = TEMPORARY_LOGIN_CODE_COOKIE_NAME) String temporaryLoginCode
    ) {
        SocialLoginTokenResult result = customerAuthUseCase.exchangeTemporaryLoginCode(temporaryLoginCode);

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
        CustomerTokenReissueResult result = customerAuthUseCase.reissueAccessToken(refreshToken);

        return ResponseEntity
                .ok()
                .header(HttpHeaders.AUTHORIZATION, BearerTokenExtractor.toAuthorizationHeader(result.accessToken()))
                .header(HttpHeaders.SET_COOKIE, AuthCookieSupport.createRefreshTokenCookie(
                        result.refreshToken(),
                        result.refreshTokenMaxAgeSeconds()
                ).toString())
                .body(TokenReissueResponse.from(result));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        customerAuthUseCase.logout(new CustomerLogoutCommand(resolveAccessToken(authorizationHeader)));

        return ResponseEntity
                .noContent()
                .header(HttpHeaders.SET_COOKIE, AuthCookieSupport
                        .createExpiredCookie(AuthCookieSupport.REFRESH_TOKEN_COOKIE_NAME)
                        .toString())
                .build();
    }

    @GetMapping("/me")
    public ResponseEntity<CustomerMeResponse> getMyInfo(
            @CurrentUserId UUID userId
    ) {
        CustomerMeView result = customerAuthUseCase.getMyInfo(userId);

        return ResponseEntity.ok(CustomerMeResponse.from(result));
    }

    @PutMapping("/me/nickname")
    public ResponseEntity<CustomerMeResponse> updateNickname(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @Valid @RequestBody CustomerNicknameUpdateRequest request
    ) {
        CustomerNicknameUpdateResult result = customerAuthUseCase.updateNickname(
                request.toCommand(resolveAccessToken(authorizationHeader))
        );

        return ResponseEntity
                .ok()
                .header(HttpHeaders.AUTHORIZATION, BearerTokenExtractor.toAuthorizationHeader(result.accessToken()))
                .body(CustomerMeResponse.from(result.customer()));
    }

    private String resolveAccessToken(String authorizationHeader) {
        return BearerTokenExtractor.extract(authorizationHeader)
                .orElseThrow(() -> new CustomerException(CustomerErrorCode.INVALID_ACCESS_TOKEN));
    }
}
