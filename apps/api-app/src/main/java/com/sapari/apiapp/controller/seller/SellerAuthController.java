package com.sapari.apiapp.controller.seller;

import lombok.RequiredArgsConstructor;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
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

import com.sapari.apiapp.controller.auth.AuthCookieSupport;
import com.sapari.apiapp.controller.auth.BearerTokenExtractor;
import com.sapari.apiapp.controller.auth.dto.response.DuplicateCheckResponse;
import com.sapari.apiapp.controller.seller.dto.request.SellerLoginRequest;
import com.sapari.apiapp.controller.seller.dto.request.SellerNicknameUpdateRequest;
import com.sapari.apiapp.controller.seller.dto.request.SellerSignupRequest;
import com.sapari.apiapp.controller.seller.dto.response.SellerLoginResponse;
import com.sapari.apiapp.controller.seller.dto.response.SellerMeResponse;
import com.sapari.apiapp.controller.seller.dto.response.SellerSignupResponse;
import com.sapari.apiapp.controller.seller.dto.response.SellerTokenReissueResponse;
import com.sapari.seller.command.SellerLogoutCommand;
import com.sapari.seller.domain.exception.SellerErrorCode;
import com.sapari.seller.domain.exception.SellerException;
import com.sapari.seller.port.SellerAuthUseCase;
import com.sapari.seller.result.SellerLoginResult;
import com.sapari.seller.result.SellerMeResult;
import com.sapari.seller.result.SellerNicknameUpdateResult;
import com.sapari.seller.result.SellerSignupResult;
import com.sapari.seller.result.SellerTokenReissueResult;

@RestController
@RequestMapping("/api/v1/sellers/auth")
@RequiredArgsConstructor
@Validated
public class SellerAuthController {

    private final SellerAuthUseCase sellerAuthUseCase;

    @Value("${jwt.refresh-token-expiration-seconds}")
    private long refreshTokenExpirationSeconds;

    @PostMapping("/signup")
    public ResponseEntity<SellerSignupResponse> signup(
            @Valid @RequestBody SellerSignupRequest request
    ) {
        SellerSignupResult result = sellerAuthUseCase.signup(request.toCommand());

        return ResponseEntity
                .created(URI.create("/api/v1/sellers/" + result.userId()))
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
                new DuplicateCheckResponse(sellerAuthUseCase.isEmailDuplicated(email))
        );
    }

    @GetMapping("/signup/check-phone")
    public ResponseEntity<DuplicateCheckResponse> checkPhoneNumber(
            @RequestParam
            @Pattern(regexp = "^0\\d{8,10}$", message = "전화번호 형식이 올바르지 않습니다.")
            String phoneNumber
    ) {
        return ResponseEntity.ok(
                new DuplicateCheckResponse(sellerAuthUseCase.isPhoneNumberDuplicated(phoneNumber))
        );
    }

    @GetMapping("/signup/check-nickname")
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
                new DuplicateCheckResponse(sellerAuthUseCase.isNicknameDuplicated(nickname))
        );
    }

    @PostMapping("/login")
    public ResponseEntity<SellerLoginResponse> login(
            @Valid @RequestBody SellerLoginRequest request
    ) {
        SellerLoginResult result = sellerAuthUseCase.login(request.toCommand());

        return ResponseEntity
                .ok()
                .header(HttpHeaders.AUTHORIZATION, BearerTokenExtractor.toAuthorizationHeader(result.accessToken()))
                .header(HttpHeaders.SET_COOKIE, AuthCookieSupport.createRefreshTokenCookie(
                        result.refreshToken(),
                        refreshTokenExpirationSeconds
                ).toString())
                .body(SellerLoginResponse.from(result));
    }

    @PostMapping("/token/reissue")
    public ResponseEntity<SellerTokenReissueResponse> reissueAccessToken(
            @CookieValue(name = AuthCookieSupport.REFRESH_TOKEN_COOKIE_NAME, required = false) String refreshToken
    ) {
        SellerTokenReissueResult result = sellerAuthUseCase.reissueAccessToken(refreshToken);

        return ResponseEntity
                .ok()
                .header(HttpHeaders.AUTHORIZATION, BearerTokenExtractor.toAuthorizationHeader(result.accessToken()))
                .body(SellerTokenReissueResponse.from(result));
    }

    @GetMapping("/me")
    public ResponseEntity<SellerMeResponse> getMyInfo(
            @AuthenticationPrincipal(expression = "user.userId") UUID userId
    ) {
        SellerMeResult result = sellerAuthUseCase.getMyInfo(userId);

        return ResponseEntity.ok(SellerMeResponse.from(result));
    }

    @GetMapping("/me/check-nickname")
    public ResponseEntity<DuplicateCheckResponse> checkMyNickname(
            @AuthenticationPrincipal(expression = "user.userId") UUID userId,
            @RequestParam
            @NotBlank(message = "닉네임은 필수입니다.")
            @Pattern(
                    regexp = "^[가-힣A-Za-z0-9]{2,10}$",
                    message = "닉네임은 2~10자의 한글, 영문, 숫자만 사용할 수 있습니다."
            )
            String nickname
    ) {
        return ResponseEntity.ok(
                new DuplicateCheckResponse(sellerAuthUseCase.isMyNicknameDuplicated(userId, nickname))
        );
    }

    @PutMapping("/me/nickname")
    public ResponseEntity<SellerMeResponse> updateNickname(
            @AuthenticationPrincipal(expression = "user.userId") UUID userId,
            @Valid @RequestBody SellerNicknameUpdateRequest request
    ) {
        SellerNicknameUpdateResult result = sellerAuthUseCase.updateNickname(request.toCommand(userId));
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();

        if (result.accessToken() != null) {
            response.header(HttpHeaders.AUTHORIZATION, BearerTokenExtractor.toAuthorizationHeader(result.accessToken()));
        }

        return response.body(SellerMeResponse.from(result.seller()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal(expression = "user.userId") UUID userId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        sellerAuthUseCase.logout(new SellerLogoutCommand(userId, resolveAccessToken(authorizationHeader)));

        return ResponseEntity
                .noContent()
                .header(HttpHeaders.SET_COOKIE, AuthCookieSupport
                        .createDeleteCookie(AuthCookieSupport.REFRESH_TOKEN_COOKIE_NAME)
                        .toString())
                .build();
    }

    private String resolveAccessToken(String authorizationHeader) {
        return BearerTokenExtractor.extract(authorizationHeader)
                .orElseThrow(() -> new SellerException(SellerErrorCode.INVALID_ACCESS_TOKEN));
    }
}
