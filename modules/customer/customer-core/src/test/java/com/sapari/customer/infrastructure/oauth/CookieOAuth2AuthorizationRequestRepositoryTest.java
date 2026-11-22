package com.sapari.customer.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

import tools.jackson.databind.ObjectMapper;

import com.sapari.global.time.TimeProvider;

@DisplayName("OAuth2 인가 요청 쿠키 저장소 테스트")
class CookieOAuth2AuthorizationRequestRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString(filled(32, (byte) 7));

    @Test
    @DisplayName("한 Pod 가 저장한 인가 요청을 같은 키를 가진 다른 Pod 가 콜백에서 읽는다")
    void otherInstanceWithSameKeyLoadsSavedRequest() {
        OAuth2AuthorizationRequest saved = authorizationRequest("state-1");
        Cookie cookie = save(repository(KEY, NOW), saved);

        OAuth2AuthorizationRequest loaded = repository(KEY, NOW.plusSeconds(60))
                .loadAuthorizationRequest(callback("state-1", cookie));

        assertThat(loaded).isNotNull();
        assertThat(loaded.getState()).isEqualTo("state-1");
        assertThat(loaded.getClientId()).isEqualTo(saved.getClientId());
        assertThat(loaded.getRedirectUri()).isEqualTo(saved.getRedirectUri());
        assertThat(loaded.getAuthorizationUri()).isEqualTo(saved.getAuthorizationUri());
        assertThat(loaded.getAuthorizationRequestUri()).isEqualTo(saved.getAuthorizationRequestUri());
        assertThat(loaded.getScopes()).isEqualTo(saved.getScopes());
        assertThat(loaded.getAttributes()).isEqualTo(saved.getAttributes());
    }

    @Test
    @DisplayName("쿠키는 HttpOnly·Secure·SameSite=Lax 이고 OAuth2 경로에 5분만 산다")
    void cookieAttributes() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        repository(KEY, NOW).saveAuthorizationRequest(authorizationRequest("s"), new MockHttpServletRequest(), response);

        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .startsWith(CookieOAuth2AuthorizationRequestRepository.COOKIE_NAME + "=")
                .contains("HttpOnly", "Secure", "SameSite=Lax", "Max-Age=300",
                        "Path=" + CookieOAuth2AuthorizationRequestRepository.COOKIE_PATH)
                // 평문이 그대로 보이면 안 된다.
                .doesNotContain("client-id", "state");
    }

    @Test
    @DisplayName("콜백 state 가 쿠키의 state 와 다르면 인가 요청이 없는 것으로 본다")
    void stateMismatchReturnsNull() {
        Cookie cookie = save(repository(KEY, NOW), authorizationRequest("state-1"));

        assertThat(repository(KEY, NOW).loadAuthorizationRequest(callback("state-2", cookie))).isNull();
    }

    @Test
    @DisplayName("다른 키로 만든 쿠키나 변조된 쿠키는 읽지 않는다")
    void otherKeyOrTamperedCookieReturnsNull() {
        Cookie cookie = save(repository(KEY, NOW), authorizationRequest("state-1"));
        // 끝 글자는 버려지는 비트가 섞일 수 있어 가운데 글자를 바꾼다.
        char[] value = cookie.getValue().toCharArray();
        value[20] = value[20] == 'A' ? 'B' : 'A';
        Cookie tampered = new Cookie(cookie.getName(), new String(value));

        assertThat(repository(OTHER_KEY, NOW).loadAuthorizationRequest(callback("state-1", cookie))).isNull();
        assertThat(repository(KEY, NOW).loadAuthorizationRequest(callback("state-1", tampered))).isNull();
        assertThat(repository(KEY, NOW).loadAuthorizationRequest(
                callback("state-1", new Cookie(cookie.getName(), "not-base64!")))).isNull();
    }

    @Test
    @DisplayName("쿠키 Max-Age 와 무관하게 암호문 안 만료 시각이 지나면 읽지 않는다")
    void expiredRequestReturnsNull() {
        Cookie cookie = save(repository(KEY, NOW), authorizationRequest("state-1"));

        assertThat(repository(KEY, NOW.plus(CookieOAuth2AuthorizationRequestRepository.COOKIE_TTL))
                .loadAuthorizationRequest(callback("state-1", cookie))).isNull();
    }

    @Test
    @DisplayName("콜백에서 꺼내면 검증 결과와 무관하게 쿠키를 지운다")
    void removeExpiresCookie() {
        CookieOAuth2AuthorizationRequestRepository repository = repository(KEY, NOW);
        Cookie cookie = save(repository, authorizationRequest("state-1"));

        MockHttpServletResponse matched = new MockHttpServletResponse();
        assertThat(repository.removeAuthorizationRequest(callback("state-1", cookie), matched)).isNotNull();
        assertThat(matched.getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");

        MockHttpServletResponse mismatched = new MockHttpServletResponse();
        assertThat(repository.removeAuthorizationRequest(callback("other", cookie), mismatched)).isNull();
        assertThat(mismatched.getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
    }

    @Test
    @DisplayName("키가 32바이트 Base64 가 아니면 기동을 거부하고 메시지에 키 원문을 싣지 않는다")
    void invalidKeyIsRejected() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new OAuth2AuthorizationRequestCookieProperties(shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(shortKey);
        assertThatThrownBy(() -> new OAuth2AuthorizationRequestCookieProperties("not base64 !"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("not base64 !");
        assertThatThrownBy(() -> new OAuth2AuthorizationRequestCookieProperties(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CookieOAuth2AuthorizationRequestRepository repository(String key, Instant now) {
        return new CookieOAuth2AuthorizationRequestRepository(
                new OAuth2AuthorizationRequestCookieProperties(key),
                new ObjectMapper(),
                new TimeProvider(Clock.fixed(now, ZoneOffset.UTC))
        );
    }

    private static Cookie save(
            CookieOAuth2AuthorizationRequestRepository repository,
            OAuth2AuthorizationRequest authorizationRequest
    ) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(authorizationRequest, new MockHttpServletRequest(), response);
        return response.getCookie(CookieOAuth2AuthorizationRequestRepository.COOKIE_NAME);
    }

    private static MockHttpServletRequest callback(String state, Cookie cookie) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/customers/auth/oauth2/code/naver");
        request.setParameter(OAuth2ParameterNames.STATE, state);
        request.setParameter(OAuth2ParameterNames.CODE, "code");
        request.setCookies(cookie);
        return request;
    }

    private static OAuth2AuthorizationRequest authorizationRequest(String state) {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://nid.naver.com/oauth2.0/authorize")
                .clientId("client-id")
                .redirectUri("http://localhost:8080/api/v1/customers/auth/oauth2/code/naver")
                .scopes(Set.of("name", "email"))
                .state(state)
                .attributes(Map.of(OAuth2ParameterNames.REGISTRATION_ID, "naver"))
                .authorizationRequestUri("https://nid.naver.com/oauth2.0/authorize?response_type=code&state=" + state)
                .build();
    }

    private static byte[] filled(int length, byte value) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }
}
