package com.sapari.live.application.port;

/**
 * RTMP Ingress 발급 결과.
 *
 * <p>{@code streamKey}는 자격증명이라 저장하지 않고 발급 시 1회 호출자에게만 전달한다(재조회는 LiveKit).
 * 실수로 로그에 흘러가는 것을 막기 위해 {@link #toString()}에서 streamKey를 마스킹한다
 * (계약: streamKey 는 절대 저장/로깅 금지 — {@code LiveRoomEntity}·AGENTS 참고).
 */
public record IngressResult(String ingressId, String rtmpUrl, String streamKey) {

    @Override
    public String toString() {
        return "IngressResult[ingressId=" + ingressId + ", rtmpUrl=" + rtmpUrl + ", streamKey=***]";
    }
}
