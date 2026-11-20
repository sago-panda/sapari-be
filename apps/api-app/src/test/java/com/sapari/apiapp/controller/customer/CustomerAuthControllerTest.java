package com.sapari.apiapp.controller.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sapari.common.web.exception.GlobalExceptionHandler;
import com.sapari.customer.command.SocialSignupCommand;
import com.sapari.customer.command.CustomerProfileImageChangeCommand;
import com.sapari.customer.domain.exception.CustomerErrorCode;
import com.sapari.customer.domain.exception.CustomerException;
import com.sapari.customer.port.CustomerAuthUseCase;
import com.sapari.customer.view.CustomerMeView;
import com.sapari.customer.view.SocialSignupResult;
import com.sapari.global.time.TimeProvider;

@DisplayName("구매자 인증 컨트롤러 테스트")
class CustomerAuthControllerTest {

    @Test
    @DisplayName("고객 컨트롤러는 ResponseEntity 없이 공통 응답을 직접 반환한다")
    void doesNotUseResponseEntity() {
        assertThat(Arrays.stream(CustomerAuthController.class.getDeclaredMethods())
                .filter(method -> method.getReturnType().equals(ResponseEntity.class)))
                .isEmpty();
    }

    @Test
    @DisplayName("고객 닉네임 중복 확인은 기존 공통 응답 계약을 유지한다")
    void checkNicknameReturnsResponseEnvelope() throws Exception {
        CustomerAuthUseCase customerAuthUseCase = mock(CustomerAuthUseCase.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CustomerAuthController(customerAuthUseCase)).build();
        when(customerAuthUseCase.isNicknameDuplicated("customer")).thenReturn(false);

        mockMvc.perform(get("/api/v1/customers/auth/check-nickname")
                        .param("nickname", "customer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.duplicated").value(false))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(customerAuthUseCase).isNicknameDuplicated("customer");
    }

    @Test
    @DisplayName("소셜 회원가입 multipart 요청에 파일이 있으면 파일 바이트를 command로 전달한다")
    void completeSocialSignupPassesMultipartProfileImageFile() throws Exception {
        // given
        CustomerAuthUseCase customerAuthUseCase = mock(CustomerAuthUseCase.class);
        CustomerAuthController controller = new CustomerAuthController(customerAuthUseCase);
        ReflectionTestUtils.setField(controller, "refreshTokenExpirationSeconds", 1209600L);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(customerAuthUseCase.completeSocialSignup(eq("signup-sid"), org.mockito.ArgumentMatchers.any(SocialSignupCommand.class)))
                .thenReturn(new SocialSignupResult(java.util.UUID.randomUUID(), "access-token", "refresh-token"));

        MockMultipartFile request = new MockMultipartFile(
                "request",
                "request.json",
                MediaType.APPLICATION_JSON_VALUE,
                """
                        {
                          "phoneNumber": "01012345678",
                          "email": "customer@example.com",
                          "nickname": "customer",
                          "name": "구매자",
                          "birthDate": "2000-01-01",
                          "gender": "FEMALE",
                          "useSocialProfileImage": false,
                          "privacyAgreed": true,
                          "marketingAgreed": false
                        }
                        """.getBytes()
        );
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "profile.png",
                MediaType.IMAGE_PNG_VALUE,
                new byte[] {1, 2, 3}
        );

        // when
        var response = mockMvc.perform(multipart("/api/v1/customers/auth/signup/social")
                        .file(request)
                        .file(file)
                        .cookie(new jakarta.servlet.http.Cookie("signup_sid", "signup-sid")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").isNotEmpty())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andReturn()
                .getResponse();

        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .anyMatch(cookie -> cookie.startsWith("refresh_token=refresh-token;"))
                .anyMatch(cookie -> cookie.startsWith("signup_sid=;"));

        // then
        ArgumentCaptor<SocialSignupCommand> commandCaptor = ArgumentCaptor.forClass(SocialSignupCommand.class);
        verify(customerAuthUseCase).completeSocialSignup(eq("signup-sid"), commandCaptor.capture());
        assertThat(commandCaptor.getValue().hasUploadedProfileImage()).isTrue();
        assertThat(commandCaptor.getValue().profileImageOriginalFilename()).isEqualTo("profile.png");
        assertThat(commandCaptor.getValue().profileImageContentType()).isEqualTo(MediaType.IMAGE_PNG_VALUE);
        assertThat(commandCaptor.getValue().profileImageContent()).containsExactly(1, 2, 3);
        assertThat(commandCaptor.getValue().useSocialProfileImage()).isFalse();
    }

    @Test
    @DisplayName("소셜 가입 시도 제어 장애는 CUSTOMER-031 실패 봉투와 503으로 응답한다")
    void completeSocialSignupReturnsServiceUnavailableWhenAttemptControlIsUnavailable() throws Exception {
        CustomerAuthUseCase customerAuthUseCase = mock(CustomerAuthUseCase.class);
        CustomerAuthController controller = new CustomerAuthController(customerAuthUseCase);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new TimeProvider(
                        Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneOffset.UTC)
                )))
                .build();
        when(customerAuthUseCase.completeSocialSignup(
                eq("signup-sid"),
                org.mockito.ArgumentMatchers.any(SocialSignupCommand.class)
        )).thenThrow(new CustomerException(CustomerErrorCode.SOCIAL_SIGNUP_ATTEMPT_CONTROL_UNAVAILABLE));
        MockMultipartFile request = new MockMultipartFile(
                "request",
                "request.json",
                MediaType.APPLICATION_JSON_VALUE,
                """
                        {
                          "phoneNumber": "01012345678",
                          "email": "customer@example.com",
                          "nickname": "customer",
                          "name": "구매자",
                          "birthDate": "2000-01-01",
                          "gender": "FEMALE",
                          "useSocialProfileImage": false,
                          "privacyAgreed": true,
                          "marketingAgreed": false
                        }
                        """.getBytes()
        );

        mockMvc.perform(multipart("/api/v1/customers/auth/signup/social")
                        .file(request)
                        .cookie(new jakarta.servlet.http.Cookie("signup_sid", "signup-sid")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.status").value(503))
                .andExpect(jsonPath("$.error.code").value("CUSTOMER-031"));
    }

    @Test
    @DisplayName("고객 프로필 이미지 변경 성공 응답을 공통 응답으로 감싸고 파일을 command로 전달한다")
    void updateProfileImageReturnsResponseEnvelopeAndPassesFile() throws Exception {
        CustomerAuthUseCase customerAuthUseCase = mock(CustomerAuthUseCase.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CustomerAuthController(customerAuthUseCase)).build();
        when(customerAuthUseCase.updateProfileImage(org.mockito.ArgumentMatchers.any(CustomerProfileImageChangeCommand.class)))
                .thenReturn(customerMeView("https://cdn.sapari.com/profile/new.png"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "profile.png", MediaType.IMAGE_PNG_VALUE, new byte[] {1, 2, 3}
        );

        mockMvc.perform(multipart("/api/v1/customers/auth/me/profile-image")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.profileImageUrl").value("https://cdn.sapari.com/profile/new.png"))
                .andExpect(jsonPath("$.error").doesNotExist());

        ArgumentCaptor<CustomerProfileImageChangeCommand> commandCaptor =
                ArgumentCaptor.forClass(CustomerProfileImageChangeCommand.class);
        verify(customerAuthUseCase).updateProfileImage(commandCaptor.capture());
        assertThat(commandCaptor.getValue().accessToken()).isEqualTo("access-token");
        assertThat(commandCaptor.getValue().originalFilename()).isEqualTo("profile.png");
        assertThat(commandCaptor.getValue().contentType()).isEqualTo(MediaType.IMAGE_PNG_VALUE);
        assertThat(commandCaptor.getValue().content()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("고객 프로필 이미지 삭제 성공 응답을 공통 응답으로 감싼다")
    void deleteProfileImageReturnsResponseEnvelope() throws Exception {
        CustomerAuthUseCase customerAuthUseCase = mock(CustomerAuthUseCase.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CustomerAuthController(customerAuthUseCase)).build();
        when(customerAuthUseCase.deleteProfileImage("access-token")).thenReturn(customerMeView(null));

        mockMvc.perform(delete("/api/v1/customers/auth/me/profile-image")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.profileImageUrl").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(customerAuthUseCase).deleteProfileImage("access-token");
    }

    @Test
    @DisplayName("고객 로그아웃은 204 빈 응답과 refresh token 만료 쿠키를 유지한다")
    void logoutExpiresRefreshTokenCookieWithoutBody() throws Exception {
        CustomerAuthUseCase customerAuthUseCase = mock(CustomerAuthUseCase.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CustomerAuthController(customerAuthUseCase)).build();

        mockMvc.perform(post("/api/v1/customers/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith("refresh_token=;"),
                        org.hamcrest.Matchers.containsString("Max-Age=0")
                )));
    }

    private CustomerMeView customerMeView(String profileImageUrl) {
        return new CustomerMeView(
                UUID.fromString("019e6e30-ea61-7392-8123-1047154d4660"),
                "customer", "구매자", LocalDate.of(2000, 1, 1), "FEMALE",
                "01012345678", profileImageUrl, "customer@example.com", "USER",
                "ACTIVE", "BASIC", 0, false, "KAKAO"
        );
    }
}
