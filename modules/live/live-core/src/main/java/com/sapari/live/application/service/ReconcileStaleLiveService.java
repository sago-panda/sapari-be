package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.EgressSummary;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.application.port.StaleLiveReconcilePolicy;
import com.sapari.live.command.EndStaleLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.EndStaleLiveUseCase;
import com.sapari.live.port.ReconcileStaleLiveUseCase;

/**
 * 방치된 Live 방 정리 — 오래된 Live 방 중 <b>활성 egress 가 없는</b> 방만 종료한다.
 *
 * <p>경과 시간은 후보를 좁힐 뿐 판정이 아니다. 정상적으로 오래 진행 중인 방송도 똑같이 오래됐으므로,
 * 시간만으로 끊으면 멀쩡한 방송을 죽인다. 실제 판정은 "LiveKit 에 이 방의 egress 가 살아 있는가"다.
 *
 * <p><b>시청자 수로 판단하지 말 것</b> — HLS 시청자는 SFU 참가자가 아니라 인기 방송도 0 으로 보이고,
 * 시청자 0 인 방송은 그 자체로 정상이다. egress 는 서버 측 녹화라 시청자 수와 무관하게 돈다.
 *
 * <p>종료는 방마다 {@link EndStaleLiveUseCase}(별도 빈)에 위임한다 — 방별 트랜잭션·행 잠금이 필요해
 * 같은 클래스의 private 메서드로 두면 self-invocation 이라 {@code @Transactional} 이 걸리지 않는다.
 * 여기에는 트랜잭션을 두지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconcileStaleLiveService implements ReconcileStaleLiveUseCase {

    private final LiveRoomRepository liveRoomRepository;
    private final LiveMediaManager liveMediaManager;
    private final EndStaleLiveUseCase endStaleLiveUseCase;
    private final StaleLiveReconcilePolicy policy;
    private final TimeProvider timeProvider;

    @Override
    public void reconcile() {
        Instant threshold = timeProvider.now().minus(policy.threshold());

        List<UUID> candidates = liveRoomRepository.findStaleLiveRoomIds(threshold, policy.batchSize());
        if (candidates.isEmpty()) {
            return;
        }

        // 조회 실패는 예외로 올라온다 — 빈 목록으로 보이면 "모든 방이 죽었다"가 되어 전부 종료시킨다.
        Set<UUID> roomsWithActiveEgress = liveMediaManager.listAllEgress().stream()
                .filter(EgressSummary::active)
                .map(egress -> parseRoomId(egress.roomName()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        int ended = 0;
        int skipped = 0;
        for (UUID roomId : candidates) {
            if (roomsWithActiveEgress.contains(roomId)) {
                continue; // 송출이 살아 있다 — 오래됐을 뿐 정상 방송
            }
            try {
                endStaleLiveUseCase.endStale(new EndStaleLiveCommand(roomId));
                ended++;
            } catch (InvalidLiveStateException | LiveNotFoundException e) {
                // 후보 조회~잠금 사이에 판매자가 직접 종료했거나 방이 사라진 경우. 다음 회차에 자연히 빠진다.
                skipped++;
                log.info("방치 Live 종료 스킵 — 이미 처리된 방. roomId={}, 사유={}", roomId, e.getClass().getSimpleName());
            }
        }
        log.info("방치된 Live 방 정리 완료. 후보={}, 종료={}, 스킵={}", candidates.size(), ended, skipped);
    }

    /** LiveKit 방 이름은 자유 문자열이라 우리 roomId 가 아닐 수 있다. */
    private UUID parseRoomId(String roomName) {
        if (roomName == null || roomName.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(roomName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
