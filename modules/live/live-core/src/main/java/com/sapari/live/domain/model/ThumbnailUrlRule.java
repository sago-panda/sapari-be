package com.sapari.live.domain.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.sapari.live.domain.exception.InvalidLiveRoomException;
import com.sapari.live.domain.exception.LiveErrorCode;

/**
 * 방 썸네일 주소 규칙 — {@code https://{CDN 호스트}{basePath}/thumbnails/{요청 판매자 ID}/{UUID}} 만 허용한다.
 *
 * <p>호스트·경로를 정확히 비교한다(접미사 위장 호스트, userInfo, 포트, 쿼리, 추가 세그먼트, 인코딩 우회 모두 거부).
 * 경로는 디코드하지 않은 원문({@code getRawPath})으로 본다. 저장소를 조회하지 않으므로 객체가 실제로 있는지는
 * 보지 않는다 — 인프라 검증 전에는 통과한 주소도 404 일 수 있다.
 *
 * <p>방 생성과 방 수정(SPR-158)이 같은 규칙을 쓴다.
 */
public record ThumbnailUrlRule(String host, int port, String basePath) {

    private static final String SCHEME = "https";
    private static final Pattern OBJECT_ID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    public ThumbnailUrlRule {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("썸네일 CDN 호스트가 비어 있습니다.");
        }
        if (basePath == null || (!basePath.isEmpty() && (!basePath.startsWith("/") || basePath.endsWith("/")))) {
            throw new IllegalArgumentException("썸네일 CDN basePath 는 비어 있거나 '/' 로 시작하고 '/' 로 끝나지 않아야 합니다: " + basePath);
        }
        host = host.toLowerCase(Locale.ROOT);
    }

    /**
     * 설정값(예: {@code https://cdn.example.com} 또는 {@code https://cdn.example.com/assets})으로 만든다.
     * 잘못된 설정은 기동 시점에 실패한다 — 클라이언트 입력이 아니다.
     */
    public static ThumbnailUrlRule of(String cdnBaseUrl) {
        URI base;
        try {
            base = new URI(cdnBaseUrl);
        } catch (URISyntaxException | NullPointerException e) {
            throw new IllegalArgumentException("썸네일 CDN 주소 형식이 잘못되었습니다: " + cdnBaseUrl, e);
        }
        if (!SCHEME.equals(base.getScheme()) || base.getHost() == null
                || base.getRawUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null) {
            throw new IllegalArgumentException("썸네일 CDN 주소는 쿼리·userInfo 없는 https 여야 합니다: " + cdnBaseUrl);
        }
        String path = base.getRawPath() == null ? "" : base.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return new ThumbnailUrlRule(base.getHost(), base.getPort(), path);
    }

    public void verify(String url, UUID sellerId) {
        if (url == null || sellerId == null || !matches(url, sellerId)) {
            throw new InvalidLiveRoomException(LiveErrorCode.INVALID_THUMBNAIL_URL, LiveErrorCode.INVALID_THUMBNAIL_URL.getMessage());
        }
    }

    public String publicUrlOf(ThumbnailKey key) {
        String portPart = port == -1 ? "" : ":" + port;
        return SCHEME + "://" + host + portPart + basePath + "/" + key.publicKey();
    }

    private boolean matches(String url, UUID sellerId) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return false; // 형식이 아닌 문자열 — 거부로 번역한다(verify 가 LIVE-016 으로 던진다)
        }
        if (!SCHEME.equals(uri.getScheme()) || uri.getHost() == null
                || !host.equals(uri.getHost().toLowerCase(Locale.ROOT)) || uri.getPort() != port
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return false;
        }
        String prefix = basePath + "/" + ThumbnailKey.PUBLIC_AREA + sellerId + "/";
        String path = uri.getRawPath();
        return path != null && path.startsWith(prefix)
                && OBJECT_ID.matcher(path.substring(prefix.length())).matches();
    }
}
