package com.sapari.liveapp.security;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpMethod;
import org.springframework.web.util.UrlPathHelper;

/**
 * 판매자 자격증명 발급 경로 판정. 선례는 같은 패키지 {@link EnterPaths} · {@link ChatHistoryPaths} 다.
 *
 * <p><b>{@code POST} 만 본다.</b> 둘 다 {@code @PostMapping} 이고, Spring MVC 가 {@code POST} 에
 * 대해 다른 메서드를 대신 서비스하지 않는다 — {@code GET} 핸들러에 {@code HEAD} 가 따라붙는
 * 형제 경로들과 다른 점이고, 그래서 {@code HEAD} 를 같이 세지 않는다.
 *
 * <p>⚠️ <b>최초 발급({@code POST /rooms/{roomId}/ingress})은 일부러 제외했다.</b>
 * {@code RtmpIngressAssigner.assignRtmpIngressIfAbsent} 가 키 없는 방에만 쓰므로 방당 한 번으로
 * 묶인다 — 재발급과 달리 같은 방으로 반복 호출해도 LiveKit 호출이 늘지 않는다. 방을 양산하는
 * 경로로 증폭하려면 방 생성 자체를 지나야 하고, 그건 이 상한이 아니라 그쪽의 몫이다.
 */
final class CredentialIssuePaths {

    private static final String THUMBNAIL_UPLOAD = "/api/v1/lives/thumbnails/uploads";
    private static final String ROOMS_PREFIX = "/api/v1/lives/rooms/";
    private static final String REISSUE_SUFFIX = "/ingress/reissue";

    private CredentialIssuePaths() {
    }

    /** {@code POST /api/v1/lives/thumbnails/uploads} */
    static boolean isThumbnailUpload(HttpServletRequest request) {
        return isPost(request) && THUMBNAIL_UPLOAD.equals(path(request));
    }

    /** {@code POST /api/v1/lives/rooms/{roomId}/ingress/reissue} */
    static boolean isIngressReissue(HttpServletRequest request) {
        if (!isPost(request)) {
            return false;
        }
        String path = path(request);
        if (!path.startsWith(ROOMS_PREFIX) || !path.endsWith(REISSUE_SUFFIX)) {
            return false;
        }
        // 가운데가 한 세그먼트(roomId)여야 한다 — 접두·접미만 보면 형제 경로를 잡을 수 있다.
        String middle = path.substring(ROOMS_PREFIX.length(), path.length() - REISSUE_SUFFIX.length());
        return !middle.isEmpty() && middle.indexOf('/') < 0;
    }

    private static boolean isPost(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod());
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
