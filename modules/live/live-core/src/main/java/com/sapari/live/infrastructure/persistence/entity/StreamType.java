package com.sapari.live.infrastructure.persistence.entity;

/**
 * 송출 입력 방식 판별자 (영속 discriminator).
 *
 * <p>도메인의 sealed {@link com.sapari.live.domain.model.LiveStreamType} 변종을 평면 컬럼으로 풀 때의
 * 태그다. {@code stream_type} 컬럼에 매핑되며, 매퍼가 이 값으로 어느 변종인지 복원한다
 * ({@code LiveRoomStatus}가 sealed {@code LiveStatus}의 태그인 것과 동일).
 */
public enum StreamType {
    WEBRTC, RTMP
}
