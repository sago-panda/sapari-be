package com.sapari.live.application.port;

/**
 * RTMP Ingress 발급 결과.
 *
 * <p>{@code streamKey}는 자격증명이라 저장하지 않고 발급 시 1회 호출자에게만 전달한다(재조회는 LiveKit).
 * 실수로 로그에 흘러가는 것을 막기 위해 {@link #toString()}에서 streamKey를 마스킹한다
 * (계약: streamKey 는 절대 저장/로깅 금지 — {@code LiveRoomEntity}·AGENTS 참고).
 */
public record IngressResult(String ingressId, String rtmpUrl, String streamKey) {

    public IngressResult {
        // 어댑터 경계에서 막는다 — 배정이 조건부 UPDATE(컬럼 직접 쓰기)라 LiveStreamType.Rtmp 의
        // 컴팩트 생성자를 거치지 않는다. 빈 값이 통과하면 stream_type=RTMP + ingress_id=NULL 행이 남아
        // (a) 다음 prepare 가 ingress_id IS NULL 을 또 통과하고
        // (b) LiveRoomMapper 가 Rtmp(null) 을 만들려다 터져 그 방을 조회조차 못 하게 된다.
        if (ingressId == null || ingressId.isBlank()) {
            throw new IllegalArgumentException("ingressId는 필수입니다.");
        }
    }

    @Override
    public String toString() {
        return "IngressResult[ingressId=" + ingressId + ", rtmpUrl=" + rtmpUrl + ", streamKey=***]";
    }
}
