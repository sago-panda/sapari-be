package com.sapari.live.domain.model;

/**
 * 판매자 송출 입력 소스 (도메인).
 *
 * <p>변종별로 자기 데이터만 보유해 불법 상태(예: RTMP인데 ingress 참조 없음)를 타입으로 차단한다.
 * 공통 미디어(sfuRoomId/egressId/hlsUrl)는 여기 두지 않고 {@link StreamInfo}가 보유한다 —
 * 이 타입은 "입력 방식 + 그 방식 고유 데이터"만 책임진다.
 *
 * <p>영속은 {@code LiveRoomEntity}의 평면 컬럼(판별자 {@code stream_type} + {@code ingress_id})으로
 * flatten 되며, 변환은 {@code LiveRoomMapper}가 담당한다(sealed {@code LiveStatus} ↔ 컬럼과 동일 패턴).
 */
public sealed interface LiveStreamType permits LiveStreamType.WebRtc, LiveStreamType.Rtmp {

    /** 브라우저/모바일 카메라가 토큰으로 SFU에 직접 publish. 추가 데이터 없음. */
    record WebRtc() implements LiveStreamType {}

    /**
     * OBS/전문 인코더가 LiveKit Ingress로 RTMP push. ingress 참조(ingressId)만 보유한다 —
     * streamKey(자격증명)는 저장하지 않고 LiveKit이 보관하며, 발급 시 1회 전달·재조회는 listIngress.
     */
    record Rtmp(String ingressId) implements LiveStreamType {
        public Rtmp {
            if (ingressId == null || ingressId.isBlank()) {
                throw new IllegalArgumentException("ingressId는 필수입니다.");
            }
        }
    }
}
