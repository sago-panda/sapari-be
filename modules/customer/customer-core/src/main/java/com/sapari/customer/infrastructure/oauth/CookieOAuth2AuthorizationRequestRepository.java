package com.sapari.customer.infrastructure.oauth;

import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.sapari.global.time.TimeProvider;

/**
 * OAuth2 인가 요청을 서버 세션 대신 AES-GCM 으로 암호화한 쿠키에 보관한다.
 * 로그인 시작과 provider 콜백이 서로 다른 api-app Pod 로 가도 같은 키로 복호화되므로 state 검증이 깨지지 않는다.
 *
 * <p>쿠키는 브라우저에 묶여 있어 state 만으로는 막지 못하는 login CSRF(공격자의 콜백 URL 을 피해자가 여는 것)도 막는다.
 * 만료 시각은 쿠키 Max-Age 와 별개로 암호문 안에도 넣어 서버가 직접 확인한다 — Max-Age 는 클라이언트가 바꿀 수 있다.
 * 콜백에서 한 번 읽으면 쿠키를 지운다(Spring 이 콜백 처리 시 {@link #removeAuthorizationRequest} 를 부른다).
 */
@Slf4j
@Component
public class CookieOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    static final String COOKIE_NAME = "oauth2_auth_request";
    // ApiSecurityConfig 의 authorizationEndpoint·redirectionEndpoint baseUri 를 모두 덮어야 콜백에 쿠키가 실린다.
    static final String COOKIE_PATH = "/api/v1/customers/auth/oauth2";
    static final Duration COOKIE_TTL = Duration.ofMinutes(5);

    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] ASSOCIATED_DATA = COOKIE_NAME.getBytes(StandardCharsets.UTF_8);

    private final SecretKey secretKey;
    private final ObjectMapper objectMapper;
    private final TimeProvider timeProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    public CookieOAuth2AuthorizationRequestRepository(
            OAuth2AuthorizationRequestCookieProperties properties,
            ObjectMapper objectMapper,
            TimeProvider timeProvider
    ) {
        this.secretKey = new SecretKeySpec(properties.encryptionKeyBytes(), "AES");
        this.objectMapper = objectMapper;
        this.timeProvider = timeProvider;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        String state = request.getParameter(OAuth2ParameterNames.STATE);
        Cookie cookie = WebUtils.getCookie(request, COOKIE_NAME);
        if (state == null || cookie == null) {
            return null;
        }

        StoredAuthorizationRequest stored = decrypt(cookie.getValue());
        if (stored == null
                || !state.equals(stored.state())
                || !timeProvider.now().isBefore(Instant.ofEpochSecond(stored.expiresAtEpochSecond()))) {
            return null;
        }
        return stored.toAuthorizationRequest();
    }

    @Override
    public void saveAuthorizationRequest(
            OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (authorizationRequest == null) {
            expireCookie(response);
            return;
        }

        Instant expiresAt = timeProvider.now().plus(COOKIE_TTL);
        String value = encrypt(StoredAuthorizationRequest.from(authorizationRequest, expiresAt));
        addCookie(response, value, COOKIE_TTL);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
        // 검증 실패여도 쿠키는 지운다 — 한 번 콜백을 탄 인가 요청은 다시 쓰지 않는다.
        expireCookie(response);
        return authorizationRequest;
    }

    private String encrypt(StoredAuthorizationRequest stored) {
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(ASSOCIATED_DATA);
            byte[] cipherText = cipher.doFinal(objectMapper.writeValueAsBytes(stored));

            byte[] payload = ByteBuffer.allocate(iv.length + cipherText.length).put(iv).put(cipherText).array();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("OAuth2 인가 요청 쿠키를 암호화할 수 없습니다.", e);
        }
    }

    /**
     * 위조·손상·다른 키로 만든 쿠키는 "인가 요청 없음"으로 본다. 그러면 Spring 이
     * {@code authorization_request_not_found} 로 인증을 실패시키고 실패 핸들러가 프론트로 돌려보낸다.
     */
    private StoredAuthorizationRequest decrypt(String value) {
        try {
            byte[] payload = Base64.getUrlDecoder().decode(value);
            if (payload.length <= IV_BYTES) {
                return null;
            }
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES));
            cipher.updateAAD(ASSOCIATED_DATA);
            byte[] plainText = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
            return objectMapper.readValue(plainText, StoredAuthorizationRequest.class);
        } catch (GeneralSecurityException | IllegalArgumentException | JacksonException e) {
            log.warn("OAuth2 인가 요청 쿠키를 복호화하지 못했습니다. cause={}", e.getClass().getSimpleName());
            return null;
        }
    }

    private void addCookie(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(true)
                // provider 에서 돌아오는 콜백은 cross-site top-level GET 이라 Strict 이면 쿠키가 실리지 않는다.
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void expireCookie(HttpServletResponse response) {
        addCookie(response, "", Duration.ZERO);
    }

    /** 쿠키에 담는 인가 요청 스냅샷. authorization_code 그랜트만 쓰므로 grantType 은 싣지 않는다. */
    record StoredAuthorizationRequest(
            String authorizationUri,
            String clientId,
            String redirectUri,
            Set<String> scopes,
            String state,
            Map<String, Object> additionalParameters,
            Map<String, Object> attributes,
            String authorizationRequestUri,
            long expiresAtEpochSecond
    ) {

        static StoredAuthorizationRequest from(OAuth2AuthorizationRequest request, Instant expiresAt) {
            return new StoredAuthorizationRequest(
                    request.getAuthorizationUri(),
                    request.getClientId(),
                    request.getRedirectUri(),
                    request.getScopes(),
                    request.getState(),
                    request.getAdditionalParameters(),
                    request.getAttributes(),
                    request.getAuthorizationRequestUri(),
                    expiresAt.getEpochSecond()
            );
        }

        OAuth2AuthorizationRequest toAuthorizationRequest() {
            return OAuth2AuthorizationRequest.authorizationCode()
                    .authorizationUri(authorizationUri)
                    .clientId(clientId)
                    .redirectUri(redirectUri)
                    .scopes(scopes)
                    .state(state)
                    .additionalParameters(additionalParameters)
                    .attributes(attributes)
                    .authorizationRequestUri(authorizationRequestUri)
                    .build();
        }
    }
}
