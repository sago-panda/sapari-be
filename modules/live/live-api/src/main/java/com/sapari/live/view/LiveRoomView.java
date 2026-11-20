package com.sapari.live.view;

import java.time.Instant;
import java.util.UUID;

/**
 * 방 단건의 권한·싱크 판정에 필요한 최소 정보. 상태는 boolean 으로만 노출한다 —
 * 상태 이름(LiveStatus)은 live 내부 지식이라 호출 도메인으로 새면 안 된다.
 *
 * @param live      지금 방송 중인가. Suspended 는 송출이 멈춘 상태이므로 false 로 본다.
 * @param startedAt 방송 시작 시각. 시작 전이면 null.
 */
public record LiveRoomView(
        UUID roomId,
        UUID sellerId,
        boolean live,
        Instant startedAt
) {}
