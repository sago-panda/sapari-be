package com.sapari.live.domain.repository;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.sapari.live.domain.model.LiveStatus;

/**
 * 관리자 방 목록의 거름 조건. 목록과 개수가 같은 값을 받아 총 개수가 목록과 어긋나지 않는다.
 *
 * @param statuses 포함할 상태 종류(비어 있으면 안 된다)
 * @param sellerId null 이면 전체 판매자
 * @param from     예정 시각 하한(포함), null 이면 없음
 * @param to       예정 시각 상한(제외), null 이면 없음
 */
public record AdminRoomFilter(
        Set<Class<? extends LiveStatus>> statuses,
        UUID sellerId,
        Instant from,
        Instant to
) {
}
