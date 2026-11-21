package com.sapari.apiapp.controller.customer;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.sapari.apiapp.controller.support.auth.AuthCookieSupport;
import com.sapari.apiapp.controller.support.auth.BearerTokenExtractor;
import com.sapari.apiapp.controller.support.multipart.ProfileImageMultipartFileReader;
import com.sapari.apiapp.controller.auth.dto.request.EmailVerificationConfirmRequest;
import com.sapari.apiapp.controller.auth.dto.request.EmailVerificationSendRequest;
import com.sapari.apiapp.controller.auth.dto.request.PhoneVerificationConfirmRequest;
import com.sapari.apiapp.controller.auth.dto.request.PhoneVerificationSendRequest;
import com.sapari.apiapp.controller.auth.dto.response.DuplicateCheckResponse;
import com.sapari.apiapp.controller.auth.dto.response.EmailVerificationConfirmResponse;
import com.sapari.apiapp.controller.auth.dto.response.EmailVerificationSendResponse;
import com.sapari.apiapp.controller.auth.dto.response.PhoneVerificationConfirmResponse;
import com.sapari.apiapp.controller.auth.dto.response.PhoneVerificationSendResponse;
import com.sapari.apiapp.controller.customer.dto.request.CustomerNicknameUpdateRequest;
import com.sapari.apiapp.controller.customer.dto.request.SocialSignupRequest;
import com.sapari.apiapp.controller.customer.dto.response.CustomerMeResponse;
import com.sapari.apiapp.controller.customer.dto.response.SocialSignupInfoResponse;
import com.sapari.apiapp.controller.customer.dto.response.SocialLoginResponse;
import com.sapari.apiapp.controller.customer.dto.response.SocialSignupResponse;
import com.sapari.apiapp.controller.customer.dto.response.TokenReissueResponse;
import com.sapari.common.response.ResponseEnvelope;
import com.sapari.common.web.security.CurrentUserId;
import com.sapari.customer.command.CustomerLogoutCommand;
import com.sapari.customer.command.CustomerProfileImageChangeCommand;
import com.sapari.customer.domain.exception.CustomerErrorCode;
import com.sapari.customer.domain.exception.CustomerException;
import com.sapari.customer.port.CustomerAuthUseCase;
import com.sapari.customer.view.CustomerMeView;
import com.sapari.customer.view.CustomerEmailVerificationConfirmResult;
import com.sapari.customer.view.CustomerEmailVerificationSendResult;
import com.sapari.customer.view.CustomerNicknameUpdateResult;
import com.sapari.customer.view.CustomerPhoneVerificationConfirmResult;
import com.sapari.customer.view.CustomerPhoneVerificationSendResult;
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

    /**
     * 소셜 가입 정보 JSON과 선택 프로필 이미지 파일을 하나의 multipart 요청으로 받아 가입을 완료한다.
     * JSON 파트는 DTO 검증을 유지하고, 파일 파트는 HTTP 계층에서 transport-neutral 데이터로 변환한다.
     */
    @PostMapping(value = "/signup/social", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEnvelope<SocialSignupResponse> completeSocialSignup(
            @CookieValue(name = SIGNUP_SID_COOKIE_NAME) String signupSid,
            @Valid @RequestPart("request") SocialSignupRequest request,
            @RequestPart(value = "file", required = false) MultipartFile file,
            HttpServletResponse response
    ) {
        // 파일 파트 부재와 빈 파트는 모두 직접 업로드 미선택(null)으로 하위 계층에 전달한다.
        ProfileImageMultipartFileReader.ProfileImageFile profileImageFile =
                file == null || file.isEmpty() ? null : ProfileImageMultipartFileReader.read(file);
        SocialSignupResult result = customerAuthUseCase.completeSocialSignup(signupSid, request.toCommand(profileImageFile));

        response.setHeader(HttpHeaders.AUTHORIZATION, BearerTokenExtractor.toAuthorizationHeader(result.accessToken()));
        response.addHeader(HttpHeaders.SET_COOKIE, AuthCookieSupport.createRefreshTokenCookie(
                result.refreshToken(),
                refreshTokenExpirationSeconds
        ).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, AuthCookieSupport.createExpiredCookie(SIGNUP_SID_COOKIE_NAME).toString());
        return ResponseEnvelope.success(SocialSignupResponse.from(result));
    }

    @GetMapping("/signup/social-info")
    public ResponseEnvelope<SocialSignupInfoResponse> getSocialSignupInfo(
            @CookieValue(name = SIGNUP_SID_COOKIE_NAME) String signupSid,
            HttpServletResponse response
    ) {
        SocialSignupInfoView result = customerAuthUseCase.getSocialSignupInfo(signupSid);

        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return ResponseEnvelope.success(SocialSignupInfoResponse.from(result));
    }

    /**
     * 구매자 회원가입 휴대폰 인증번호를 발송한다.
     * 서버는 Redis 쿨다운을 먼저 원자 선점한 요청에 한해 SOLAPI 발송을 진행한다.
     */
    @PostMapping("/signup/phone-verifications")
    public ResponseEnvelope<PhoneVerificationSendResponse> sendSignupPhoneVerification(
            @Valid @RequestBody PhoneVerificationSendRequest request
    ) {
        CustomerPhoneVerificationSendResult result = customerAuthUseCase.sendSignupPhoneVerification(request.toCommand());

        return ResponseEnvelope.success(PhoneVerificationSendResponse.from(result));
    }

    /**
     * 구매자 회원가입 휴대폰 인증번호를 확인한다.
     * 인증 성공 시 회원가입 API가 소비할 서버 verified 상태를 생성한다.
     */
    @PostMapping("/signup/phone-verifications/confirm")
    public ResponseEnvelope<PhoneVerificationConfirmResponse> confirmSignupPhoneVerification(
            @Valid @RequestBody PhoneVerificationConfirmRequest request
    ) {
        CustomerPhoneVerificationConfirmResult result =
                customerAuthUseCase.confirmSignupPhoneVerification(request.toCommand());

        return ResponseEnvelope.success(PhoneVerificationConfirmResponse.from(result));
    }

    /**
     * 구매자 회원가입 이메일 인증번호를 발송한다.
     * 서버는 Resend 발송 성공 후에만 Redis codeHash를 저장한다.
     */
    @PostMapping("/signup/email-verifications")
    public ResponseEnvelope<EmailVerificationSendResponse> sendSignupEmailVerification(
            @Valid @RequestBody EmailVerificationSendRequest request
    ) {
        CustomerEmailVerificationSendResult result = customerAuthUseCase.sendSignupEmailVerification(request.toCustomerCommand());

        return ResponseEnvelope.success(EmailVerificationSendResponse.from(result));
    }

    /**
     * 구매자 회원가입 이메일 인증번호를 확인한다.
     * emailVerified=true 응답은 화면 상태용이며, 최종 가입 API가 Redis verified 상태를 다시 소비한다.
     */
    @PostMapping("/signup/email-verifications/confirm")
    public ResponseEnvelope<EmailVerificationConfirmResponse> confirmSignupEmailVerification(
            @Valid @RequestBody EmailVerificationConfirmRequest request
    ) {
        CustomerEmailVerificationConfirmResult result =
                customerAuthUseCase.confirmSignupEmailVerification(request.toCustomerCommand());

        return ResponseEnvelope.success(EmailVerificationConfirmResponse.from(result));
    }

    @GetMapping("/signup/check-phone")
    public ResponseEnvelope<DuplicateCheckResponse> checkPhoneNumber(
            @RequestParam
            @Pattern(regexp = "^010\\d{8}$", message = "전화번호는 010으로 시작하는 숫자 11자리여야 합니다.")
            String phoneNumber
    ) {
        return ResponseEnvelope.success(
                new DuplicateCheckResponse(customerAuthUseCase.isPhoneNumberDuplicated(phoneNumber))
        );
    }

    @GetMapping("/signup/check-email")
    public ResponseEnvelope<DuplicateCheckResponse> checkEmail(
            @RequestParam
            @NotBlank(message = "이메일은 필수입니다.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            String email
    ) {
        return ResponseEnvelope.success(
                new DuplicateCheckResponse(customerAuthUseCase.isEmailDuplicated(email))
        );
    }

    @GetMapping("/check-nickname")
    public ResponseEnvelope<DuplicateCheckResponse> checkNickname(
            @RequestParam
            @NotBlank(message = "닉네임은 필수입니다.")
            @Pattern(
                    regexp = "^[가-힣A-Za-z0-9]{2,10}$",
                    message = "닉네임은 2~10자의 한글, 영문, 숫자만 사용할 수 있습니다."
            )
            String nickname
    ) {
        return ResponseEnvelope.success(
                new DuplicateCheckResponse(customerAuthUseCase.isNicknameDuplicated(nickname))
        );
    }

    @PostMapping("/login/social/code")
    public ResponseEnvelope<SocialLoginResponse> exchangeSocialLoginCode(
            @CookieValue(name = TEMPORARY_LOGIN_CODE_COOKIE_NAME) String temporaryLoginCode,
            HttpServletResponse response
    ) {
        SocialLoginTokenResult result = customerAuthUseCase.exchangeTemporaryLoginCode(temporaryLoginCode);

        response.setHeader(HttpHeaders.AUTHORIZATION, BearerTokenExtractor.toAuthorizationHeader(result.accessToken()));
        response.addHeader(HttpHeaders.SET_COOKIE, AuthCookieSupport.createRefreshTokenCookie(
                result.refreshToken(),
                refreshTokenExpirationSeconds
        ).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, AuthCookieSupport
                .createExpiredCookie(TEMPORARY_LOGIN_CODE_COOKIE_NAME)
                .toString());
        return ResponseEnvelope.success(SocialLoginResponse.from(result));
    }

    @PostMapping("/token/reissue")
    public ResponseEnvelope<TokenReissueResponse> reissueAccessToken(
            @CookieValue(name = AuthCookieSupport.REFRESH_TOKEN_COOKIE_NAME) String refreshToken,
            HttpServletResponse response
    ) {
        CustomerTokenReissueResult result = customerAuthUseCase.reissueAccessToken(refreshToken);

        response.setHeader(HttpHeaders.AUTHORIZATION, BearerTokenExtractor.toAuthorizationHeader(result.accessToken()));
        response.addHeader(HttpHeaders.SET_COOKIE, AuthCookieSupport.createRefreshTokenCookie(
                result.refreshToken(),
                result.refreshTokenMaxAgeSeconds()
        ).toString());
        return ResponseEnvelope.success(TokenReissueResponse.from(result));
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/logout")
    public void logout(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            HttpServletResponse response
    ) {
        customerAuthUseCase.logout(new CustomerLogoutCommand(resolveAccessToken(authorizationHeader)));

        response.addHeader(HttpHeaders.SET_COOKIE, AuthCookieSupport
                .createExpiredCookie(AuthCookieSupport.REFRESH_TOKEN_COOKIE_NAME)
                .toString());
    }

    @GetMapping("/me")
    public ResponseEnvelope<CustomerMeResponse> getMyInfo(
            @CurrentUserId UUID userId
    ) {
        CustomerMeView result = customerAuthUseCase.getMyInfo(userId);

        return ResponseEnvelope.success(CustomerMeResponse.from(result));
    }

    /**
     * 현재 로그인한 고객의 회원탈퇴를 신청한다.
     * 탈퇴 처리 후 모든 기기 세션이 폐기되므로 클라이언트의 refresh token 쿠키도 즉시 만료시킨다.
     */
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/me")
    public void withdraw(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION) String authorizationHeader,
            HttpServletResponse response
    ) {
        customerAuthUseCase.requestWithdrawal(resolveAccessToken(authorizationHeader));

        response.addHeader(HttpHeaders.SET_COOKIE, AuthCookieSupport
                .createExpiredCookie(AuthCookieSupport.REFRESH_TOKEN_COOKIE_NAME)
                .toString());
    }

    @PutMapping("/me/nickname")
    public ResponseEnvelope<CustomerMeResponse> updateNickname(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @Valid @RequestBody CustomerNicknameUpdateRequest request,
            HttpServletResponse response
    ) {
        CustomerNicknameUpdateResult result = customerAuthUseCase.updateNickname(
                request.toCommand(resolveAccessToken(authorizationHeader))
        );

        response.setHeader(HttpHeaders.AUTHORIZATION, BearerTokenExtractor.toAuthorizationHeader(result.accessToken()));
        return ResponseEnvelope.success(CustomerMeResponse.from(result.customer()));
    }

    /** 인증된 고객의 이미지 파일을 multipart로 받아 검증·저장하고 갱신된 내 정보를 반환한다. */
    @PutMapping(value = "/me/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEnvelope<CustomerMeResponse> updateProfileImage(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestPart("file") MultipartFile file
    ) {
        ProfileImageMultipartFileReader.ProfileImageFile profileImageFile =
                ProfileImageMultipartFileReader.read(file);
        CustomerMeView result = customerAuthUseCase.updateProfileImage(new CustomerProfileImageChangeCommand(
                resolveAccessToken(authorizationHeader),
                profileImageFile.originalFilename(),
                profileImageFile.contentType(),
                profileImageFile.content()
        ));

        return ResponseEnvelope.success(CustomerMeResponse.from(result));
    }

    /** 인증된 고객의 DB 이미지 key를 비우고 기존 object 정리를 요청한다. */
    @DeleteMapping("/me/profile-image")
    public ResponseEnvelope<CustomerMeResponse> deleteProfileImage(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION) String authorizationHeader
    ) {
        CustomerMeView result = customerAuthUseCase.deleteProfileImage(resolveAccessToken(authorizationHeader));

        return ResponseEnvelope.success(CustomerMeResponse.from(result));
    }

    private String resolveAccessToken(String authorizationHeader) {
        return BearerTokenExtractor.extract(authorizationHeader)
                .orElseThrow(() -> new CustomerException(CustomerErrorCode.INVALID_ACCESS_TOKEN));
    }
}
