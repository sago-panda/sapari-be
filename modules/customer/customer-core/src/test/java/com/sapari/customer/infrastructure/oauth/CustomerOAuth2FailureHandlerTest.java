package com.sapari.customer.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;

@DisplayName("구매자 OAuth2 실패 핸들러 테스트")
class CustomerOAuth2FailureHandlerTest {

    private static final String FAILURE_URL = "https://front.example/login/failure";

    private final CustomerOAuth2FailureHandler handler =
            new CustomerOAuth2FailureHandler(redirectProperties(FAILURE_URL));

    @Test
    @DisplayName("사용자가 동의를 취소하면 access_denied 코드로 프론트에 돌려보내고 provider 설명은 싣지 않는다")
    void accessDeniedRedirectsWithFixedCode() throws Exception {
        MockHttpServletResponse response = fail(new OAuth2AuthenticationException(
                new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED, "user canceled: secret detail", null)));

        assertThat(response.getRedirectedUrl()).isEqualTo(FAILURE_URL + "?error=access_denied");
    }

    @Test
    @DisplayName("그 밖의 OAuth2 실패는 login_failed 하나로 돌려보낸다")
    void otherOAuth2ErrorsRedirectWithLoginFailed() throws Exception {
        MockHttpServletResponse notFound = fail(new OAuth2AuthenticationException(
                new OAuth2Error("authorization_request_not_found", "detail", null)));
        MockHttpServletResponse invalidToken = fail(new OAuth2AuthenticationException(
                new OAuth2Error("invalid_token_response", "provider said: token error", null)));
        MockHttpServletResponse nonOAuth2 = fail(new AuthenticationServiceException("boom"));

        assertThat(notFound.getRedirectedUrl()).isEqualTo(FAILURE_URL + "?error=login_failed");
        assertThat(invalidToken.getRedirectedUrl()).isEqualTo(FAILURE_URL + "?error=login_failed");
        assertThat(nonOAuth2.getRedirectedUrl()).isEqualTo(FAILURE_URL + "?error=login_failed");
    }

    @Test
    @DisplayName("실패 URL 이 없거나 http(s) 절대 URL 이 아니면 기동을 거부한다")
    void invalidFailureUrlIsRejected() {
        for (String url : new String[] {null, "", " ", "/login/failure", "javascript:alert(1)", "front.example/fail"}) {
            assertThatThrownBy(() -> new CustomerOAuth2FailureHandler(redirectProperties(url)))
                    .as(String.valueOf(url))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private MockHttpServletResponse fail(org.springframework.security.core.AuthenticationException exception)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationFailure(new MockHttpServletRequest(), response, exception);
        return response;
    }

    private static CustomerOAuthRedirectProperties redirectProperties(String failureUrl) {
        CustomerOAuthRedirectProperties properties = new CustomerOAuthRedirectProperties();
        properties.setFailureUrl(failureUrl);
        return properties;
    }
}
