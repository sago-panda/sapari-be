package com.sapari.apiapp.controller.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sapari.seller.command.SellerProfileImageChangeCommand;
import com.sapari.seller.model.SellerApprovalStatus;
import com.sapari.seller.model.SellerBusinessType;
import com.sapari.seller.port.SellerAuthUseCase;
import com.sapari.seller.view.SellerMeView;

@DisplayName("판매자 인증 컨트롤러 테스트")
class SellerAuthControllerTest {

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
