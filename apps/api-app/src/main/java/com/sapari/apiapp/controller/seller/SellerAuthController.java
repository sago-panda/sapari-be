package com.sapari.apiapp.controller.seller;

import lombok.RequiredArgsConstructor;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.sapari.apiapp.controller.support.auth.AuthCookieSupport;
import com.sapari.apiapp.controller.support.auth.BearerTokenExtractor;
import com.sapari.apiapp.controller.support.multipart.ProfileImageMultipartFileReader;
import com.sapari.apiapp.controller.auth.dto.request.EmailVerificationConfirmRequest;
import com.sapari.apiapp.controller.auth.dto.request.EmailVerificationSendRequest;
import com.sapari.apiapp.controller.auth.dto.response.DuplicateCheckResponse;
import com.sapari.apiapp.controller.auth.dto.response.EmailVerificationConfirmResponse;
import com.sapari.apiapp.controller.auth.dto.response.EmailVerificationSendResponse;
import com.sapari.apiapp.controller.seller.dto.request.SellerLoginRequest;
import com.sapari.apiapp.controller.seller.dto.request.SellerNicknameUpdateRequest;
import com.sapari.apiapp.controller.seller.dto.request.SellerSignupRequest;
import com.sapari.apiapp.controller.seller.dto.response.SellerLoginResponse;
import com.sapari.apiapp.controller.seller.dto.response.SellerMeResponse;
import com.sapari.apiapp.controller.seller.dto.response.SellerSignupResponse;
import com.sapari.apiapp.controller.seller.dto.response.SellerTokenReissueResponse;
import com.sapari.common.response.ResponseEnvelope;
import com.sapari.common.web.security.CurrentUserId;
import com.sapari.seller.command.SellerLogoutCommand;
import com.sapari.seller.command.SellerProfileImageChangeCommand;
import com.sapari.seller.domain.exception.SellerErrorCode;
import com.sapari.seller.domain.exception.SellerException;
import com.sapari.seller.port.SellerAuthUseCase;
import com.sapari.seller.view.SellerLoginResult;
import com.sapari.seller.view.SellerEmailVerificationConfirmResult;
import com.sapari.seller.view.SellerEmailVerificationSendResult;
import com.sapari.seller.view.SellerMeView;
import com.sapari.seller.view.SellerNicknameUpdateResult;
import com.sapari.seller.view.SellerSignupResult;
import com.sapari.seller.view.SellerTokenReissueResult;


@RestController
@RequestMapping("/api/v1/sellers/auth")
@RequiredArgsConstructor
@Validated
public class SellerAuthController {

    private final SellerAuthUseCase sellerAuthUseCase;

    @Value("${jwt.refresh-token-expiration-seconds}")
    private long refreshTokenExpirationSeconds;

    @PostMapping("/signup")
    public ResponseEntity<ResponseEnvelope<SellerSignupResponse>> signup(
            @Valid @RequestBody SellerSignupRequest request
    ) {
        SellerSignupResult result = sellerAuthUseCase.signup(request.toCommand());

        return ResponseEntity
                .created(URI.create("/api/v1/sellers/" + result.userId()))
                .body(ResponseEnvelope.success(SellerSignupResponse.from(result)));
    }

    /**
     * 판매자 회원가입 이메일 인증번호를 발송한다.
     * 이메일은 로그인 ID이자 운영 안내 수신 채널이므로, 가입 이메일 자체의 수신 가능성을 확인한다.
     * 다른 이메일 우회 인증은 허용하지 않고, 회사 메일 정책으로 수신이 불가능하면 관리자 문의로 처리한다.
     */
    @PostMapping("/signup/email-verifications")
    public ResponseEntity<ResponseEnvelope<EmailVerificationSendResponse>> sendSignupEmailVerification(
            @Valid @RequestBody EmailVerificationSendRequest request
    ) {
        SellerEmailVerificationSendResult result = sellerAuthUseCase.sendSignupEmailVerification(request.toSellerCommand());

        return ResponseEntity.ok(ResponseEnvelope.success(EmailVerificationSendResponse.from(result)));
    }

    /**
     * 판매자 회원가입 이메일 인증번호를 확인한다.
     * 인증 email은 가입 요청 email과 같아야 하며, emailVerified=true 응답은 화면 상태용이다.
     * 최종 가입 API가 같은 email의 Redis verified 상태를 다시 소비한다.
     */
    @PostMapping("/signup/email-verifications/confirm")
    public ResponseEntity<ResponseEnvelope<EmailVerificationConfirmResponse>> confirmSignupEmailVerification(
            @Valid @RequestBody EmailVerificationConfirmRequest request
    ) {
        SellerEmailVerificationConfirmResult result =
                sellerAuthUseCase.confirmSignupEmailVerification(request.toSellerCommand());

        return ResponseEntity.ok(ResponseEnvelope.success(EmailVerificationConfirmResponse.from(result)));
    }

    @GetMapping("/signup/check-email")
    public ResponseEntity<ResponseEnvelope<DuplicateCheckResponse>> checkEmail(
            @RequestParam
            @NotBlank(message = "이메일은 필수입니다.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            String email
    ) {
        return ResponseEntity.ok(ResponseEnvelope.success(
                new DuplicateCheckResponse(sellerAuthUseCase.isEmailDuplicated(email))
        ));
    }

    @GetMapping("/signup/check-phone")
    public ResponseEntity<ResponseEnvelope<DuplicateCheckResponse>> checkPhoneNumber(
            @RequestParam
            @Pattern(regexp = "^0\\d{8,10}$", message = "전화번호 형식이 올바르지 않습니다.")
            String phoneNumber
    ) {
        return ResponseEntity.ok(ResponseEnvelope.success(
                new DuplicateCheckResponse(sellerAuthUseCase.isPhoneNumberDuplicated(phoneNumber))
        ));
    }

    @GetMapping("/check-nickname")
    public ResponseEntity<ResponseEnvelope<DuplicateCheckResponse>> checkNickname(
            @RequestParam
            @NotBlank(message = "닉네임은 필수입니다.")
            @Pattern(
                    regexp = "^[가-힣A-Za-z0-9]{2,10}$",
                    message = "닉네임은 2~10자의 한글, 영문, 숫자만 사용할 수 있습니다."
            )
            String nickname
    ) {
        return ResponseEntity.ok(ResponseEnvelope.success(
                new DuplicateCheckResponse(sellerAuthUseCase.isNicknameDuplicated(nickname))
        ));
    }

    @GetMapping("/signup/check-store-name")
    public ResponseEntity<ResponseEnvelope<DuplicateCheckResponse>> checkStoreName(
            @RequestParam
            @NotBlank(message = "상호명은 필수입니다.")
            @Size(max = 20, message = "상호명은 20자 이하여야 합니다.")
            String storeName
    ) {
        return ResponseEntity.ok(ResponseEnvelope.success(
                new DuplicateCheckResponse(sellerAuthUseCase.isStoreNameDuplicated(storeName))
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<ResponseEnvelope<SellerLoginResponse>> login(
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
                .body(ResponseEnvelope.success(SellerLoginResponse.from(result)));
    }

    @PostMapping("/token/reissue")
    public ResponseEntity<ResponseEnvelope<SellerTokenReissueResponse>> reissueAccessToken(
            @CookieValue(name = AuthCookieSupport.REFRESH_TOKEN_COOKIE_NAME) String refreshToken
    ) {
        SellerTokenReissueResult result = sellerAuthUseCase.reissueAccessToken(refreshToken);

        return ResponseEntity
                .ok()
                .header(HttpHeaders.AUTHORIZATION, BearerTokenExtractor.toAuthorizationHeader(result.accessToken()))
                .header(HttpHeaders.SET_COOKIE, AuthCookieSupport.createRefreshTokenCookie(
                        result.refreshToken(),
                        result.refreshTokenMaxAgeSeconds()
                ).toString())
                .body(ResponseEnvelope.success(SellerTokenReissueResponse.from(result)));
    }

    @GetMapping("/me")
    public ResponseEntity<ResponseEnvelope<SellerMeResponse>> getMyInfo(
            @CurrentUserId UUID userId
    ) {
        SellerMeView result = sellerAuthUseCase.getMyInfo(userId);

        return ResponseEntity.ok(ResponseEnvelope.success(SellerMeResponse.from(result)));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION) String authorizationHeader
    ) {
        sellerAuthUseCase.requestWithdrawal(resolveAccessToken(authorizationHeader));

        return ResponseEntity
                .noContent()
                .header(HttpHeaders.SET_COOKIE, AuthCookieSupport
                        .createExpiredCookie(AuthCookieSupport.REFRESH_TOKEN_COOKIE_NAME)
                        .toString())
                .build();
    }

    @PutMapping("/me/nickname")
    public ResponseEntity<ResponseEnvelope<SellerMeResponse>> updateNickname(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @Valid @RequestBody SellerNicknameUpdateRequest request
    ) {
        SellerNicknameUpdateResult result = sellerAuthUseCase.updateNickname(
                request.toCommand(resolveAccessToken(authorizationHeader))
        );

        return ResponseEntity
                .ok()
                .header(HttpHeaders.AUTHORIZATION, BearerTokenExtractor.toAuthorizationHeader(result.accessToken()))
                .body(ResponseEnvelope.success(SellerMeResponse.from(result.seller())));
    }

    /** 인증된 판매자의 이미지 파일을 multipart로 받아 검증·저장하고 갱신된 내 정보를 반환한다. */
    @PutMapping(value = "/me/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResponseEnvelope<SellerMeResponse>> updateProfileImage(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestPart("file") MultipartFile file
    ) {
        ProfileImageMultipartFileReader.ProfileImageFile profileImageFile =
                ProfileImageMultipartFileReader.read(file);
        SellerMeView result = sellerAuthUseCase.updateProfileImage(new SellerProfileImageChangeCommand(
                resolveAccessToken(authorizationHeader),
                profileImageFile.originalFilename(),
                profileImageFile.contentType(),
                profileImageFile.content()
        ));

        return ResponseEntity.ok(ResponseEnvelope.success(SellerMeResponse.from(result)));
    }

    /** 인증된 판매자의 DB 이미지 key를 비우고 기존 object 정리를 요청한다. */
    @DeleteMapping("/me/profile-image")
    public ResponseEntity<ResponseEnvelope<SellerMeResponse>> deleteProfileImage(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION) String authorizationHeader
    ) {
        SellerMeView result = sellerAuthUseCase.deleteProfileImage(resolveAccessToken(authorizationHeader));

        return ResponseEntity.ok(ResponseEnvelope.success(SellerMeResponse.from(result)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        sellerAuthUseCase.logout(new SellerLogoutCommand(resolveAccessToken(authorizationHeader)));

        return ResponseEntity
                .noContent()
                .header(HttpHeaders.SET_COOKIE, AuthCookieSupport
                        .createExpiredCookie(AuthCookieSupport.REFRESH_TOKEN_COOKIE_NAME)
                        .toString())
                .build();
    }

    private String resolveAccessToken(String authorizationHeader) {
        return BearerTokenExtractor.extract(authorizationHeader)
                .orElseThrow(() -> new SellerException(SellerErrorCode.INVALID_ACCESS_TOKEN));
    }
}
