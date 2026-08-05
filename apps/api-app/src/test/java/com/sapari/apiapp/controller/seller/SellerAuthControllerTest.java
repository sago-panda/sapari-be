package com.sapari.apiapp.controller.seller;

import static org.assertj.core.api.Assertions.assertThat;
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

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sapari.seller.command.SellerProfileImageChangeCommand;
import com.sapari.seller.model.SellerApprovalStatus;
import com.sapari.seller.model.SellerBusinessType;
import com.sapari.seller.port.SellerAuthUseCase;
import com.sapari.seller.view.SellerMeView;
import com.sapari.seller.view.SellerSignupResult;

@DisplayName("판매자 인증 컨트롤러 테스트")
class SellerAuthControllerTest {

    @Test
    @DisplayName("판매자 컨트롤러는 201 Location 응답에만 ResponseEntity를 사용한다")
    void usesResponseEntityOnlyForCreatedLocation() {
        assertThat(Arrays.stream(SellerAuthController.class.getDeclaredMethods())
                .filter(method -> method.getReturnType().equals(ResponseEntity.class)))
                .extracting(Method::getName)
                .containsExactly("signup");
    }

    @Test
    @DisplayName("판매자 상호명 중복 확인은 기존 공통 응답 계약을 유지한다")
    void checkStoreNameReturnsResponseEnvelope() throws Exception {
        SellerAuthUseCase sellerAuthUseCase = mock(SellerAuthUseCase.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SellerAuthController(sellerAuthUseCase)).build();
        when(sellerAuthUseCase.isStoreNameDuplicated("사파리 상점")).thenReturn(false);

        mockMvc.perform(get("/api/v1/sellers/auth/signup/check-store-name")
                        .param("storeName", "사파리 상점"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.duplicated").value(false))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(sellerAuthUseCase).isStoreNameDuplicated("사파리 상점");
    }

    @Test
    @DisplayName("판매자 가입은 201 Location과 공통 응답 봉투를 유지한다")
    void signupReturnsCreatedLocationAndResponseEnvelope() throws Exception {
        SellerAuthUseCase sellerAuthUseCase = mock(SellerAuthUseCase.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SellerAuthController(sellerAuthUseCase)).build();
        UUID userId = UUID.fromString("019e6e30-ea61-7392-8123-1047154d4661");
        when(sellerAuthUseCase.signup(org.mockito.ArgumentMatchers.any())).thenReturn(new SellerSignupResult(userId));

        mockMvc.perform(post("/api/v1/sellers/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "seller@example.com",
                                  "password": "Password1!",
                                  "passwordConfirm": "Password1!",
                                  "nickname": "seller",
                                  "name": "판매자",
                                  "phoneNumber": "01087654321",
                                  "privacyAgreed": true,
                                  "marketingAgreed": false,
                                  "storeName": "사파리 상점",
                                  "businessNumber": "1234567890",
                                  "businessStartDate": "2020-01-01",
                                  "businessType": "INDIVIDUAL"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/sellers/" + userId))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(userId.toString()))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("판매자 프로필 이미지 변경 성공 응답을 공통 응답으로 감싸고 파일을 command로 전달한다")
    void updateProfileImageReturnsResponseEnvelopeAndPassesFile() throws Exception {
        SellerAuthUseCase sellerAuthUseCase = mock(SellerAuthUseCase.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SellerAuthController(sellerAuthUseCase)).build();
        when(sellerAuthUseCase.updateProfileImage(org.mockito.ArgumentMatchers.any(SellerProfileImageChangeCommand.class)))
                .thenReturn(sellerMeView("https://cdn.sapari.com/profile/seller.png"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "seller.png", MediaType.IMAGE_PNG_VALUE, new byte[] {4, 5, 6}
        );

        mockMvc.perform(multipart("/api/v1/sellers/auth/me/profile-image")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer seller-token")
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.profileImageUrl").value("https://cdn.sapari.com/profile/seller.png"))
                .andExpect(jsonPath("$.error").doesNotExist());

        ArgumentCaptor<SellerProfileImageChangeCommand> commandCaptor =
                ArgumentCaptor.forClass(SellerProfileImageChangeCommand.class);
        verify(sellerAuthUseCase).updateProfileImage(commandCaptor.capture());
        assertThat(commandCaptor.getValue().accessToken()).isEqualTo("seller-token");
        assertThat(commandCaptor.getValue().originalFilename()).isEqualTo("seller.png");
        assertThat(commandCaptor.getValue().contentType()).isEqualTo(MediaType.IMAGE_PNG_VALUE);
        assertThat(commandCaptor.getValue().content()).containsExactly(4, 5, 6);
    }

    @Test
    @DisplayName("판매자 프로필 이미지 삭제 성공 응답을 공통 응답으로 감싼다")
    void deleteProfileImageReturnsResponseEnvelope() throws Exception {
        SellerAuthUseCase sellerAuthUseCase = mock(SellerAuthUseCase.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SellerAuthController(sellerAuthUseCase)).build();
        when(sellerAuthUseCase.deleteProfileImage("seller-token")).thenReturn(sellerMeView(null));

        mockMvc.perform(delete("/api/v1/sellers/auth/me/profile-image")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer seller-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.profileImageUrl").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(sellerAuthUseCase).deleteProfileImage("seller-token");
    }

    @Test
    @DisplayName("판매자 로그아웃은 204 빈 응답과 refresh token 만료 쿠키를 유지한다")
    void logoutExpiresRefreshTokenCookieWithoutBody() throws Exception {
        SellerAuthUseCase sellerAuthUseCase = mock(SellerAuthUseCase.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SellerAuthController(sellerAuthUseCase)).build();

        mockMvc.perform(post("/api/v1/sellers/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer seller-token"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith("refresh_token=;"),
                        org.hamcrest.Matchers.containsString("Max-Age=0")
                )));
    }

    private SellerMeView sellerMeView(String profileImageUrl) {
        return new SellerMeView(
                UUID.fromString("019e6e30-ea61-7392-8123-1047154d4661"),
                "seller", "판매자", LocalDate.of(1990, 1, 1), "01087654321",
                profileImageUrl, "seller@example.com", "SELLER", "ACTIVE", "BASIC",
                0, false, "사파리 상점", "1234567890", SellerBusinessType.INDIVIDUAL,
                SellerApprovalStatus.APPROVED, null, null
        );
    }
}
