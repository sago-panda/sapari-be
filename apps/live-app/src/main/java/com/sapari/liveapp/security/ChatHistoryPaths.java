package com.sapari.liveapp.security;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpMethod;
import org.springframework.web.util.UrlPathHelper;

/**
 * 공개 채팅 이력 조회 경로 판정. 선례는 같은 패키지 {@link EnterPaths} 다.
 *
 * <p>⚠️ <b>{@code EnterPaths} 를 넓히지 않는다.</b> 그러면 입장 상한과 이력 상한이 같은 설정·같은
 * 버킷을 쓰게 되고, 한쪽의 폭주가 다른 쪽의 예산을 먹는다 — 이 저장소가 사유별 래치·사유별
 * 카운터로 반복해서 고친 바로 그 결함 모양이다({@code EntryGate} · {@code SendChatService} ·
 * {@code ChatSessionRegistry}). 경로 판정도 설정도 버킷도 따로 둔다.
 *
 * <p><b>{@code GET} 과 {@code HEAD} 를 둘 다 본다.</b> Spring MVC 는 {@code @GetMapping} 에 대해
 * {@code HEAD} 를 서비스하므로, 메서드를 {@code GET} 으로만 보면 {@code HEAD} 가 상한 밖으로
 * 빠진다 — 본문을 안 보내도 <b>조회 비용은 그대로 든다</b>(Postgres 1 + Mongo 1).
 */
final class ChatHistoryPaths {

    private static final String PREFIX = "/api/v1/lives/";
    private static final String SUFFIX = "/chat/history";

    private ChatHistoryPaths() {
    }

    static boolean isHistoryRead(HttpServletRequest request) {
        String method = request.getMethod();
        if (!HttpMethod.GET.matches(method) && !HttpMethod.HEAD.matches(method)) {
            return false;
        }
        String path = path(request);
        if (!path.startsWith(PREFIX) || !path.endsWith(SUFFIX)) {
            return false;
        }
        // 가운데가 한 세그먼트(roomId)여야 한다 — 접두·접미만 보면 형제 경로를 잡을 수 있다.
        String middle = path.substring(PREFIX.length(), path.length() - SUFFIX.length());
        return !middle.isEmpty() && middle.indexOf('/') < 0;
    }

    /** 잘못된 인코딩이 들어오면 빈 경로로 본다 — 상한은 그 요청을 거르지 않고 뒤로 넘긴다({@link EnterPaths} 와 같다). */
    private static String path(HttpServletRequest request) {
        try {
            return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
