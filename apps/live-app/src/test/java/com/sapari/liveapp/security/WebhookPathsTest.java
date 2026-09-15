package com.sapari.liveapp.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.RequestPath;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * webhook 경로 판정.
 *
 * <p>여기서 지키는 불변식은 하나다: <b>필터의 판정이 라우팅의 판정과 같아야 한다.</b> 특정 우회 문자열
 * 하나를 막는 테스트로는 부족하다 — 그 방식이 원래 결함을 만들었다. 그래서 적대적 경로 표를 두고,
 * 매 경로마다 {@link WebhookPaths} 와 <b>Spring 이 실제로 쓰는 매처</b>({@link PathPattern})를 대조한다.
 * 한쪽만 바뀌면 여기서 깨진다.
 *
 * <p>{@code MockHttpServletRequest.setRequestURI()} 에는 <b>원시(인코딩된) 값</b>을 넣는다. 디코딩된 값을
 * 넣으면 실제 서블릿 컨테이너의 동작과 달라져, 바로 그 결함이 테스트에 보이지 않는다.
 */
class WebhookPathsTest {

    /** 라우팅·시큐리티가 이 경로에 적용하는 매칭. 필터 판정의 정답지 역할을 한다. */
    private static final PathPattern ROUTING = new PathPatternParser().parse("/webhooks/**");

    private record Case(String rawUri, String contextPath, String why) {
    }

    private static MockHttpServletRequest request(Case c) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", c.rawUri());
        request.setRequestURI(c.rawUri());
        request.setContextPath(c.contextPath());
        return request;
    }

    private static boolean routesToWebhook(Case c) {
        String withinApp = c.rawUri().substring(c.contextPath().length());
        return ROUTING.matches(RequestPath.parse(withinApp, "").pathWithinApplication());
    }

    @Test
    @DisplayName("필터 판정이 라우팅 판정과 모든 경로에서 일치한다 — 어긋나는 순간 필터가 조용히 꺼진다")
    void filterDecisionMatchesRouting() {
        List<Case> cases = List.of(
                new Case("/webhooks/livekit", "", "정상"),
                // %77 = 'w'. 라우팅은 디코딩해서 매칭하므로 컨트롤러에 도달한다 —
                // 원시 경로로 비교하던 옛 구현은 여기서 통째로 뚫렸다.
                new Case("/%77ebhooks/livekit", "", "퍼센트 인코딩 우회"),
                new Case("/webhooks/%6Civekit", "", "경로 뒷부분 인코딩"),
                // context-path 가 잡히면 getRequestURI() 에는 그게 포함돼 접두사 비교가 항상 실패한다
                new Case("/live/webhooks/livekit", "/live", "context-path"),
                new Case("/api/v1/lives/42", "", "webhook 아님"),
                new Case("/webhooksX/livekit", "", "접두사만 비슷"),
                new Case("/", "", "루트")
        );

        for (Case c : cases) {
            assertThat(WebhookPaths.isWebhookRequest(request(c)))
                    .describedAs("%s (%s)", c.rawUri(), c.why())
                    .isEqualTo(routesToWebhook(c));
        }
    }

    @Test
    @DisplayName("깨진 인코딩은 예외가 아니라 '아님'으로 떨어진다 — 던지면 미인증 500 을 무한정 만들 수 있다")
    void malformedEncodingDoesNotThrow() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/%zz");
        request.setRequestURI("/webhooks/%zz");

        assertThat(WebhookPaths.isWebhookRequest(request)).isFalse();
    }
}
