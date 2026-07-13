package com.sapari.live.application.port;

/**
 * LiveKit webhook 요청의 서명을 검증하고 도메인 이벤트로 변환하는 아웃바운드 포트.
 *
 * <p>구현은 LiveKit SDK({@code WebhookReceiver})로 {@code Authorization} 헤더의 JWT 서명을 검증한다.
 * 위변조·서명 불일치 시 예외를 던져 거부한다(신뢰할 수 없는 요청은 라우팅하지 않는다).
 */
public interface WebhookVerifier {

    /**
     * @param body        webhook 요청 raw 본문(원본 바이트)
     * @param authHeader  {@code Authorization} 헤더 값(LiveKit 서명 JWT, 없으면 null)
     * @return 검증된 이벤트
     */
    LiveWebhookEvent verify(byte[] body, String authHeader);
}
