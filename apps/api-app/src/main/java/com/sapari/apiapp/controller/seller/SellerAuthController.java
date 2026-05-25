package com.sapari.apiapp.controller.seller;

import lombok.RequiredArgsConstructor;

import java.net.URI;
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

import com.sapari.apiapp.controller.seller.dto.request.SellerLoginRequest;
import com.sapari.apiapp.controller.seller.dto.request.SellerMeUpdateRequest;
import com.sapari.apiapp.controller.seller.dto.request.SellerSignupRequest;
import com.sapari.apiapp.controller.seller.dto.response.DuplicateCheckResponse;
import com.sapari.apiapp.controller.seller.dto.response.SellerLoginResponse;
import com.sapari.apiapp.controller.seller.dto.response.SellerMeResponse;
import com.sapari.apiapp.controller.seller.dto.response.SellerSignupResponse;
import com.sapari.apiapp.controller.seller.dto.response.SellerTokenReissueResponse;
import com.sapari.seller.command.SellerLogoutCommand;
import com.sapari.seller.domain.exception.SellerErrorCode;
import com.sapari.seller.domain.exception.SellerException;
import com.sapari.seller.port.SellerAuthFacade;
import com.sapari.seller.result.SellerLoginResult;
import com.sapari.seller.result.SellerMeResult;
import com.sapari.seller.result.SellerSignupResult;
import com.sapari.seller.result.SellerTokenReissueResult;

@RestController
@RequestMapping("/api/sellers")
@RequiredArgsConstructor
@Validated
public class SellerAuthController {

    private static final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";
    private static final String BEARER_PREFIX = "Bearer ";

    private final SellerAuthFacade sellerAuthFacade;

    @Value("${jwt.refresh-token-expiration-seconds}")
    private long refreshTokenExpirationSeconds;

    @PostMapping("/signup")
    public ResponseEntity<SellerSignupResponse> signup(
            @Valid @RequestBody SellerSignupRequest request
    ) {
        SellerSignupResult result = sellerAuthFacade.signup(request.toCommand());

        return ResponseEntity
                .created(URI.create("/api/sellers/" + result.userId()))
                .body(SellerSignupResponse.from(result));
    }

    @GetMapping("/signup/check-email")
    public ResponseEntity<DuplicateCheckResponse> checkEmail(
            @RequestParam
            @NotBlank(message = "이메일은 필수입니다.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            String email
    ) {
        return ResponseEntity.ok(
                new DuplicateCheckResponse(sellerAuthFacade.isEmailDuplicated(email))
        );
    }

    @GetMapping("/signup/check-phone")
    public ResponseEntity<DuplicateCheckResponse> checkPhoneNumber(
            @RequestParam
            @Pattern(regexp = "^\\d{11}$", message = "전화번호는 숫자 11자리여야 합니다.")
            String phoneNumber
    ) {
        return ResponseEntity.ok(
                new DuplicateCheckResponse(sellerAuthFacade.isPhoneNumberDuplicated(phoneNumber))
        );
    }

    @PostMapping("/login")
    public ResponseEntity<SellerLoginResponse> login(
            @Valid @RequestBody SellerLoginRequest request
    ) {
        SellerLoginResult result = sellerAuthFacade.login(request.toCommand());

        return ResponseEntity
                .ok()
                .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + result.accessToken())
                .header(HttpHeaders.SET_COOKIE, createRefreshTokenCookie(result.refreshToken()).toString())
                .body(SellerLoginResponse.from(result));
    }

    @PostMapping("/reissue")
    public ResponseEntity<SellerTokenReissueResponse> reissueAccessToken(
            @CookieValue(name = REFRESH_TOKEN_COOKIE_NAME, required = false) String refreshToken
    ) {
        SellerTokenReissueResult result = sellerAuthFacade.reissueAccessToken(refreshToken);

        return ResponseEntity
                .ok()
                .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + result.accessToken())
                .body(SellerTokenReissueResponse.from(result));
    }

    @GetMapping("/me")
    public ResponseEntity<SellerMeResponse> getMyInfo(
            @AuthenticationPrincipal(expression = "user.userId") UUID userId
    ) {
        SellerMeResult result = sellerAuthFacade.getMyInfo(userId);

        return ResponseEntity.ok(SellerMeResponse.from(result));
    }

    @PutMapping("/me")
    public ResponseEntity<SellerMeResponse> updateMyInfo(
            @AuthenticationPrincipal(expression = "user.userId") UUID userId,
            @Valid @RequestBody SellerMeUpdateRequest request
    ) {
        SellerMeResult result = sellerAuthFacade.updateMyInfo(request.toCommand(userId));

        return ResponseEntity.ok(SellerMeResponse.from(result));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal(expression = "user.userId") UUID userId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        sellerAuthFacade.logout(new SellerLogoutCommand(userId, resolveAccessToken(authorizationHeader)));

        return ResponseEntity
                .noContent()
                .header(HttpHeaders.SET_COOKIE, createDeleteCookie(REFRESH_TOKEN_COOKIE_NAME).toString())
                .build();
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
            throw new SellerException(SellerErrorCode.INVALID_ACCESS_TOKEN);
        }

        return authorizationHeader.substring(BEARER_PREFIX.length());
    }
}
