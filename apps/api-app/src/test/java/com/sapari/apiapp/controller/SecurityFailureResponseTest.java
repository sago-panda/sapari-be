package com.sapari.apiapp.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sapari.apiapp.config.ApiSecurityConfig;
import com.sapari.apiapp.config.WebMvcConfig;
import com.sapari.apiapp.controller.member.MemberAuthController;
import com.sapari.apiapp.controller.seller.SellerAuthController;
import com.sapari.common.web.security.AccessTokenRevocationChecker;
import com.sapari.common.web.security.jwt.JwtTokenProvider;
import com.sapari.global.time.TimeProvider;
import com.sapari.member.infrastructure.oauth.MemberOAuth2SuccessHandler;
import com.sapari.member.infrastructure.oauth.MemberOAuth2UserService;
import com.sapari.member.port.MemberAuthUseCase;
import com.sapari.seller.port.SellerAuthUseCase;

@WebMvcTest(controllers = {MemberAuthController.class, SellerAuthController.class})
@Import({
        ApiSecurityConfig.class,
        WebMvcConfig.class,
        TimeProvider.class,
        SecurityFailureResponseTest.FixedClockConfig.class
})
@DisplayName("인증/인가 실패 응답 테스트")
class SecurityFailureResponseTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberAuthUseCase memberAuthUseCase;

    @MockitoBean
    private SellerAuthUseCase sellerAuthUseCase;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private AccessTokenRevocationChecker accessTokenRevocationChecker;

    @MockitoBean
    private MemberOAuth2UserService memberOAuth2UserService;

    @MockitoBean
    private MemberOAuth2SuccessHandler memberOAuth2SuccessHandler;

    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @Test
    @DisplayName("미인증 사용자가 회원 보호 API에 접근하면 401 ErrorResponse를 반환한다")
    void unauthenticatedMemberRequestReturnsUnauthorizedErrorResponse() throws Exception {
        mockMvc.perform(get("/api/v1/members/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."));
    }

    @Test
    @DisplayName("USER 권한 사용자가 판매자 보호 API에 접근하면 403 ErrorResponse를 반환한다")
    void userRoleSellerRequestReturnsForbiddenErrorResponse() throws Exception {
        mockMvc.perform(get("/api/v1/sellers/auth/me")
                        .with(user("019e6e30-ea61-7392-8123-1047154d4660").roles("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("접근 권한이 없습니다."));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @SpringBootConfiguration
    static class TestApplication {
    }
}
