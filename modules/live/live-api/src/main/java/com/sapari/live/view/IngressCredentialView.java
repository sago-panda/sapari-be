package com.sapari.live.view;

/**
 * RTMP 송출 자격 — 판매자가 OBS 등 인코더에 입력할 rtmpUrl·streamKey.
 *
 * <p>streamKey 는 자격증명이라 응답으로 1회만 전달하고 저장/재조회하지 않는다.
 * 실수로 로그에 흘러가는 것을 막기 위해 {@link #toString()}에서 streamKey 를 마스킹한다.
 */
public record IngressCredentialView(
        String ingressId,
        String rtmpUrl,
        String streamKey
) {
    @Override
    public String toString() {
        return "IngressCredentialView[ingressId=" + ingressId + ", rtmpUrl=" + rtmpUrl + ", streamKey=***]";
    }
}
