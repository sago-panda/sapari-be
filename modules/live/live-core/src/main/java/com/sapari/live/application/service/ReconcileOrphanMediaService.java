package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.EgressSummary;
import com.sapari.live.application.port.IngressSummary;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.application.port.OrphanMediaReconcilePolicy;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.ReconcileOrphanMediaUseCase;

/**
 * 고아 미디어 회수 — LiveKit 전체 목록을 DB 와 대조해 정본이 아닌 리소스를 지운다.
 *
 * <p>DB 는 읽기만 한다. 그래서 {@code @Transactional} 이 없다 — 전이가 없어 원자성 요구가 없고,
 * 외부 호출이 목록당 여러 건이라 트랜잭션에 감싸면 커넥션만 오래 쥔다.
 *
 * <p><b>판정이 애매하면 지우지 않는다.</b> 지우는 실수는 살아 있는 방송을 끊지만, 안 지우는 실수는
 * 다음 회차가 만회한다. DB 에 행이 없는 리소스를 로그만 남기고 두는 것도 같은 이유다 —
 * {@code createIngress} 는 끝났고 {@code save} 는 아직인 정상 요청이 정확히 그렇게 보인다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconcileOrphanMediaService implements ReconcileOrphanMediaUseCase {

    private final LiveMediaManager liveMediaManager;
    private final LiveRoomRepository liveRoomRepository;
    private final OrphanMediaReconcilePolicy policy;
    private final TimeProvider timeProvider;

    @Override
    public void reconcile() {
        Instant threshold = timeProvider.now().minus(policy.grace());

        // 조회 실패는 예외로 올라온다(빈 목록이면 "고아 없음"으로 오독되므로) — 이 회차는 통째로 실패시킨다.
        List<IngressSummary> ingresses = liveMediaManager.listAllIngress();
        List<EgressSummary> egresses = liveMediaManager.listAllEgress();

        Map<UUID, LiveRoom> rooms = loadRooms(ingresses, egresses);

        reconcileIngresses(ingresses, rooms, threshold);
        reconcileEgresses(egresses, rooms, threshold);
    }

    /** 두 목록에 등장한 방을 한 번에 읽는다(방마다 조회하면 LiveKit 리소스 수만큼 쿼리가 나간다). */
    private Map<UUID, LiveRoom> loadRooms(List<IngressSummary> ingresses, List<EgressSummary> egresses) {
        Set<UUID> roomIds = Stream.concat(
                        ingresses.stream().map(IngressSummary::roomName),
                        egresses.stream().map(EgressSummary::roomName))
                .map(this::parseRoomId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return liveRoomRepository.findAllByIds(roomIds).stream()
                .collect(Collectors.toMap(LiveRoom::id, Function.identity()));
    }

    private void reconcileIngresses(List<IngressSummary> ingresses, Map<UUID, LiveRoom> rooms, Instant threshold) {
        int deleted = 0;
        for (IngressSummary ingress : ingresses) {
            UUID roomId = parseRoomId(ingress.roomName());
            if (roomId == null) {
                continue; // 우리 방 이름 규칙이 아님 — 남의 리소스일 수 있으니 손대지 않는다
            }
            LiveRoom room = rooms.get(roomId);
            if (room == null) {
                log.warn("DB 에 없는 ingress — 삭제하지 않음. roomName={}, ingressId={}",
                        ingress.roomName(), ingress.ingressId());
                continue;
            }
            // 송출 중이면 DB 가 뭐라 하든 건드리지 않는다. 지우는 순간 진행 중인 방송이 끊긴다.
            if (ingress.publishing()) {
                continue;
            }
            // ingress 는 LiveKit 이 생성 시각을 주지 않아 방의 updated_at 으로 나이를 잰다.
            if (room.updatedAt() == null || room.updatedAt().isAfter(threshold)) {
                continue;
            }
            if (!isOrphanIngress(room, ingress.ingressId())) {
                continue;
            }
            liveMediaManager.deleteIngress(roomId, ingress.ingressId());
            deleted++;
        }
        log.info("고아 ingress 정리 완료. 전체={}, 삭제={}", ingresses.size(), deleted);
    }

    /** 방이 인정하는 ingress 는 "종료되지 않은 방의 ingress_id" 하나뿐. 그 외는 전부 고아다. */
    private boolean isOrphanIngress(LiveRoom room, String ingressId) {
        if (room.status() instanceof LiveStatus.Ended) {
            return true;
        }
        return !(room.streamType() instanceof LiveStreamType.Rtmp rtmp)
                || !rtmp.ingressId().equals(ingressId);
    }

    private void reconcileEgresses(List<EgressSummary> egresses, Map<UUID, LiveRoom> rooms, Instant threshold) {
        // stopHlsEgress 는 방 단위 일괄이라 같은 방을 여러 번 부르지 않도록 모아서 한 번씩 호출한다.
        Set<UUID> roomsToStop = new LinkedHashSet<>();
        for (EgressSummary egress : egresses) {
            UUID roomId = parseRoomId(egress.roomName());
            if (roomId == null) {
                continue;
            }
            if (!egress.active()) {
                continue; // 이미 멈춘 egress — 비용이 안 나가므로 대상 아님
            }
            LiveRoom room = rooms.get(roomId);
            if (room == null) {
                log.warn("DB 에 없는 egress — 중단하지 않음. roomName={}, egressId={}",
                        egress.roomName(), egress.egressId());
                continue;
            }
            // startedAt 이 없으면(아직 시작 전) 나이를 알 수 없다 — 판정 불가는 건드리지 않는다.
            if (egress.startedAt() == null || egress.startedAt().isAfter(threshold)) {
                continue;
            }
            // Live 인 방의 egress 만 정상이다. Scheduled/Ready 방에 정당한 egress 는 존재하지 않는다.
            if (room.status() instanceof LiveStatus.Live) {
                continue;
            }
            roomsToStop.add(roomId);
        }
        roomsToStop.forEach(liveMediaManager::stopHlsEgress);
        log.info("고아 egress 정리 완료. 전체={}, 중단한 방={}", egresses.size(), roomsToStop.size());
    }

    /** LiveKit 방 이름은 자유 문자열이라 우리 roomId 가 아닐 수 있다. 파싱 실패는 대상 제외. */
    private UUID parseRoomId(String roomName) {
        if (roomName == null || roomName.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(roomName);
        } catch (IllegalArgumentException e) {
            log.debug("LiveKit 방 이름이 roomId 형식이 아님 — 대상 제외. roomName={}", roomName);
            return null;
        }
    }
}
