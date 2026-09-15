package com.sapari.liveapp.security;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.util.UrlPathHelper;

/**
 * "이 요청이 webhook 경로인가"를 <b>한 곳에서만</b> 판정한다.
 *
 * <p>필터마다 따로 판정하면 갈라진다 — 실제로 그랬다. 두 필터가 각각 {@code getRequestURI()} 로 접두사를
 * 비교했는데, 그 값은 <b>디코딩되지 않은 원시 경로</b>이고 라우팅·Spring Security 는 <b>디코딩된 경로</b>로
 * 매칭한다. 그래서 {@code POST /%77ebhooks/livekit}({@code %77} = {@code w})이 두 필터를 모두 빠져나가면서
 * 컨트롤러에는 정상 도달했다. 미인증 경로라 레이트리밋이 통째로 무력화된다.
 *
 * <p>{@link UrlPathHelper} 는 <b>한 번 디코딩하고 컨텍스트 패스를 벗겨</b> 라우팅이 보는 것과 같은 경로를
 * 준다. 컨텍스트 패스까지 함께 처리되는 게 중요하다 — {@code getRequestURI()} 는 이를 포함하므로
 * {@code server.servlet.context-path} 가 설정되는 순간 접두사 비교가 <b>항상 실패</b>해 필터가 경고 없이
 * 조용히 꺼진다({@code application*.yml} 이 미추적이라 눈치챌 방법도 없다).
 *
 * <p><b>이중 인코딩({@code %2577} 등)에서는 이 판정이 라우팅보다 좁다.</b> 여기서 디코딩은 한 번뿐이라
 * {@code %2577ebhooks} 는 {@code %77ebhooks} 로 남아 webhook 이 아닌 것으로 본다. 그래도 안전한 이유는
 * <b>정렬이 맞아서가 아니라 그런 요청이 컨트롤러까지 가지 못하기 때문</b>이다 — Spring Security 의
 * {@code StrictHttpFirewall} 이 {@code %25}·{@code %2F}·{@code .} 세그먼트·{@code ;} 를 거부한다(실측 확인).
 *
 * <p>순서를 헷갈리지 말 것: <b>방화벽은 이 필터들보다 뒤에 선다.</b> 시큐리티 체인은
 * {@code spring.security.filter.order} 기본값 {@code -100} 이고 이 필터들은
 * {@code Ordered.HIGHEST_PRECEDENCE}({@code Integer.MIN_VALUE}) 다. 즉 방화벽이 앞단에서 경로를
 * 정규화해 주는 게 아니라, <b>우리가 덜 매칭한 요청을 뒤에서 떨어뜨려 주는 것</b>이다. 그래서 "덜
 * 매칭"은 손해가 없지만 <b>"더 매칭"(라우팅은 아닌데 우리는 webhook 으로 보는 것)은 여전히 결함</b>이다.
 *
 * <p><b>{@code allowUrlEncodedPercent} 같은 완화 옵션을 켜면 이 전제가 깨진다</b> — 방화벽이 통과시킨
 * 이중 인코딩 경로가 컨트롤러에 도달하는데 여기서는 걸러지지 않는다. 켤 일이 생기면 함께 재검토할 것.
 */
final class WebhookPaths {

    private static final String WEBHOOK_PREFIX = "/webhooks/";

    private WebhookPaths() {
    }

    static boolean isWebhookRequest(HttpServletRequest request) {
        return path(request).startsWith(WEBHOOK_PREFIX);
    }

    /**
     * 라우팅이 보는 것과 같은(디코딩·컨텍스트 패스 제거된) 애플리케이션 내 경로.
     *
     * <p>깨진 퍼센트 인코딩({@code /%zz})은 디코딩이 실패할 수 있다. 그때 예외를 그대로 올리면 미인증
     * 공격자가 500 을 무한정 만들 수 있으므로 <b>빈 경로로 낮춘다</b> — webhook 이 아닌 것으로 취급된다.
     * 안전한 방향이다: 같은 입력은 Spring 의 경로 파싱에서도 실패해 컨트롤러까지 가지 못한다.
     */
    private static String path(HttpServletRequest request) {
        try {
            return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
