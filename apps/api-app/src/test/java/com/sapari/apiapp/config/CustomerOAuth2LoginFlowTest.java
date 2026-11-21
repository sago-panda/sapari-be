package com.sapari.apiapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;
import com.sapari.common.securityjwt.store.SessionRevocationChecker;
import com.sapari.customer.infrastructure.oauth.CookieOAuth2AuthorizationRequestRepository;
import com.sapari.customer.infrastructure.oauth.CustomerOAuth2Configuration;
import com.sapari.customer.infrastructure.oauth.CustomerOAuth2FailureHandler;
import com.sapari.customer.infrastructure.oauth.CustomerOAuth2SuccessHandler;
import com.sapari.customer.infrastructure.oauth.CustomerOAuth2UserService;
import com.sapari.customer.infrastructure.oauth.CustomerOAuthRedirectProperties;
import com.sapari.global.time.TimeProvider;

/**
 * 실제 보안 필터 체인으로 소셜 로그인 시작 → provider 콜백을 태워, 인가 요청이 서버 세션 없이 쿠키만으로
 * 이어지는지(다른 Pod 가 콜백을 받아도 되는지)와 실패 시 프론트 오류 경로로 가는지 확인한다.
 * 토큰 교환(외부 호출)이 필요 없는 provider 오류 콜백으로 검증한다.
 */
@WebMvcTest
@Import({
        ApiSecurityConfig.class,
        CustomerOAuth2Configuration.class,
        CookieOAuth2AuthorizationRequestRepository.class,
        CustomerOAuth2FailureHandler.class,
        CustomerOAuthRedirectProperties.class,
        TimeProvider.class,
        CustomerOAuth2LoginFlowTest.TestBeans.class
})
@TestPropertySource(properties = {
        "customer.oauth2.authorization-request-cookie.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "customer.oauth2.redirect.failure-url=https://front.example/login/failure"
})
@DisplayName("구매자 소셜 로그인 흐름(쿠키 인가 요청·실패 리다이렉트) 테스트")
class CustomerOAuth2LoginFlowTest {

    private static final String AUTHORIZATION_URI = "/api/v1/customers/auth/oauth2/authorization/naver";
    private static final String CALLBACK_URI = "/api/v1/customers/auth/oauth2/code/naver";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private AccessTokenRevocationChecker accessTokenRevocationChecker;

    @MockitoBean
    private SessionRevocationChecker sessionRevocationChecker;

    @MockitoBean
    private CustomerOAuth2UserService customerOAuth2UserService;

    @MockitoBean
    private CustomerOAuth2SuccessHandler customerOAuth2SuccessHandler;

    @Test
    @DisplayName("로그인 시작은 서버 세션을 만들지 않고 암호화 쿠키에 인가 요청을 담는다")
    void authorizationStartUsesCookieNotSession() throws Exception {
        MvcResult result = mockMvc.perform(get(AUTHORIZATION_URI))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl()).startsWith("https://nid.naver.com/oauth2.0/authorize");
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getCookie("oauth2_auth_request")).isNotNull();
    }

    @Test
    @DisplayName("사용자가 동의를 취소하면 쿠키로 state 를 확인한 뒤 고정 코드로 프론트 오류 경로에 보내고 쿠키를 지운다")
    void providerAccessDeniedRedirectsToFrontWithoutProviderMessage() throws Exception {
        MvcResult start = mockMvc.perform(get(AUTHORIZATION_URI)).andReturn();
        Cookie cookie = start.getResponse().getCookie("oauth2_auth_request");
        String state = URLDecoder.decode(UriComponentsBuilder.fromUriString(start.getResponse().getRedirectedUrl())
                .build().getQueryParams().getFirst("state"), StandardCharsets.UTF_8);

        // 세션이 없으므로 이 요청을 어느 Pod 가 받든 쿠키와 키만으로 처리된다.
        MvcResult callback = mockMvc.perform(get(CALLBACK_URI)
                        .param("error", "access_denied")
                        .param("error_description", "provider-secret-detail")
                        .param("state", state)
                        .cookie(cookie))
                .andExpect(redirectedUrl("https://front.example/login/failure?error=access_denied"))
                .andReturn();

        assertThat(callback.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .anyMatch(header -> header.startsWith("oauth2_auth_request=;") && header.contains("Max-Age=0"));
    }

    @Test
    @DisplayName("인가 요청 쿠키 없이 콜백이 오면 login_failed 로 프론트 오류 경로에 보낸다")
    void callbackWithoutCookieRedirectsWithLoginFailed() throws Exception {
        mockMvc.perform(get(CALLBACK_URI).param("code", "code").param("state", "state"))
                .andExpect(redirectedUrl("https://front.example/login/failure?error=login_failed"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("naver")
                    .clientId("client-id")
                    .clientSecret("client-secret")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/api/v1/customers/auth/oauth2/code/{registrationId}")
                    .authorizationUri("https://nid.naver.com/oauth2.0/authorize")
                    .tokenUri("https://nid.naver.com/oauth2.0/token")
                    .userInfoUri("https://openapi.naver.com/v1/nid/me")
                    .userNameAttributeName("response")
                    .build());
        }
    }

    @SpringBootConfiguration
    static class TestApplication {
    }
}
