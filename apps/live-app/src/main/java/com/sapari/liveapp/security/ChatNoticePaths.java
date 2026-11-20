package com.sapari.liveapp.security;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpMethod;
import org.springframework.web.util.UrlPathHelper;

/**
 * 공개 현재 공지 조회 경로 판정. 형제 {@link ChatHistoryPaths} 와 같은 모양이다.
 *
 * <p>⚠️ <b>{@code ChatHistoryPaths} 를 넓히지 않는다.</b> 공지는 라이브 입장마다 불리고 이력은
 * 다시보기에서만 불린다 — 버킷을 합치면 입장 몰림이 다시보기 이력을 막는다(반대도 같다). 경로
 * 판정도 설정도 버킷도 따로 둔다. {@code GET} 과 {@code HEAD} 를 둘 다 보는 이유도 형제와 같다.
 */
final class ChatNoticePaths {

    private static final String PREFIX = "/api/v1/lives/";
    private static final String SUFFIX = "/chat/notice";

    private ChatNoticePaths() {
    }

    static boolean isNoticeRead(HttpServletRequest request) {
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

    /** 잘못된 인코딩이 들어오면 빈 경로로 본다 — 형제와 같다. */
    private static String path(HttpServletRequest request) {
        try {
            return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
