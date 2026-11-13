package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.live.domain.model.VodStatus;
import com.sapari.live.domain.repository.LiveRoomRepository;

/**
 * 다시보기 처리 상태 갱신 — 조건부 UPDATE 한 문장이라 트랜잭션 안에 외부 호출이 없고, 커밋 뒤 할 일도 없다.
 * 단조성(중복·역순 무시)은 저장소의 WHERE 절이 보장한다. updated_at 은 건드리지 않는다 — 고아 미디어 정리 잡이
 * 그 값을 "방을 마지막으로 손댄 시각"으로 유예 판정에 쓴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpdateVodStatusService {

    private final LiveRoomRepository liveRoomRepository;

    @Transactional
    public void advance(String egressId, VodStatus target, String failedReason) {
        boolean updated = liveRoomRepository.advanceVodStatus(egressId, target, failedReason);
        if (!updated) {
            // 중복·역순 알림, 또는 방이 기록하지 않은 egress(비대표 화질·egressId 커밋 전 도착) — 정상이다.
            log.debug("vod_status 갱신 없음: egressId={}, target={}", egressId, target);
        }
    }
}
