package com.sapari.liveapp.security;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpMethod;
import org.springframework.web.util.UrlPathHelper;

/**
 * 입장 엔드포인트 경로 판정. 선례는 같은 패키지 {@code WebhookPaths} 다.
 *
 * <p>⚠️ <b>접두사만 보면 형제 경로까지 잡는다.</b> {@code /api/v1/lives/rooms} 아래에는
 * 목록 조회({@code /rooms})와 다시보기({@code /rooms/{id}/replay})가 같이 있고 둘 다 룸 토큰을
 * 찍지 않는다 — 상한을 그쪽에 걸면 막는 비용이 없는 경로를 막는다. 그래서 <b>세그먼트 수와
 * 메서드까지</b> 본다: {@code GET} · {@code /api/v1/lives/rooms/{한 세그먼트}}.
 */
final class EnterPaths {

    private static final String PREFIX = "/api/v1/lives/rooms/";

    private EnterPaths() {
    }

    static boolean isEnterRequest(HttpServletRequest request) {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return false;
        }
        String path = path(request);
        if (!path.startsWith(PREFIX)) {
            return false;
        }
        String rest = path.substring(PREFIX.length());
        // 남은 것이 한 세그먼트여야 한다 — 비어 있으면 /rooms/ 이고, '/' 가 있으면 /replay 류다.
        return !rest.isEmpty() && rest.indexOf('/') < 0;
    }

    /** 잘못된 인코딩이 들어오면 빈 경로로 본다 — 상한은 그 요청을 거르지 않고 뒤로 넘긴다. */
    private static String path(HttpServletRequest request) {
        try {
            return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
