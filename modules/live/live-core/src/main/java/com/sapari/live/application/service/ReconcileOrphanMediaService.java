package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
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
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.OrphanMediaReconcilePolicy;
import com.sapari.live.application.port.ReconcileAction;
import com.sapari.live.application.port.ReconcileJob;
import com.sapari.live.application.port.RoomSummary;
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
    private final LiveMetrics liveMetrics;

    /**
     * 회차를 감싸 예외를 <b>세고 다시 던진다</b>. 스케줄러가 잡아 로그를 남기는 구조는 그대로 두고
     * (실패 처리 방식을 바꾸지 않는다) 밖에서 보이는 사실만 하나 늘린다 — 이게 없으면 매 회차
     * 예외로 죽는 잡과 스케줄러가 아예 안 도는 상황이 지표상 똑같다.
     */
    @Override
    public void reconcile() {
        // 예외를 잡지 않는다 — 정상 종료에만 표시를 남기고, 없으면 실패로 센다. catch 로 세면
        // Error 로 죽은 회차가 무기록이 되어(= 스케줄러가 안 돈 것과 같아 보임) 이 지표를 만든
        // 이유가 사라지고, Throwable 을 잡으면 죽어가는 JVM 을 건드린다.
        boolean completed = false;
        try {
            doReconcile();
            completed = true;
        } finally {
            if (!completed) {
                liveMetrics.reconcileRoundFailed(ReconcileJob.ORPHAN_MEDIA);
            }
        }
    }

    private void doReconcile() {
        Instant startedAt = timeProvider.now();
        Instant threshold = startedAt.minus(policy.grace());

        // 조회 실패는 예외로 올라온다(빈 목록이면 "고아 없음"으로 오독되므로).
        List<IngressSummary> ingresses = liveMediaManager.listAllIngress();
        List<EgressSummary> egresses = liveMediaManager.listAllEgress();

        // 게이지 갱신을 이 잡이 맡는 이유: 방치 Live 잡도 같은 값을 관측하지만 <b>후보가 있을 때만</b>
        // 거기까지 간다. 방치된 방은 평소 0건이라 그 경로는 대개 조기 반환하고, 게이지는 -1 이나 낡은
        // 값에 고정된 채 live.room.active 옆에 그려져 <b>없는 괴리를 있는 것처럼</b> 보이게 한다.
        // 이 잡은 조건 없이 매 회차 전수 조회를 하므로 갱신이 보장된다.
        //
        // 이 잡에 "전 목록이 비었다" 중단 가드를 두지 않는 것도 여기서 메운다. 세 목록이 모두 비는 건
        // 방송이 없는 새벽에 매일 일어나는 정상 상태라 그걸 aborted 로 세면 알람이 매일 울리고,
        // 사람이 알람을 끄고, 진짜 오설정 때도 안 보게 된다. 대신 오설정이면 이 게이지가 0 으로
        // 떨어지고 DB 쪽 live.room.active 는 그대로라, 괴리가 그래프에 그대로 남는다.
        // 단위는 <b>방 수</b>다. egress 레코드 수를 세면 안 된다 — 한 방송이 화질별로 여러 egress 를
        // 띄우므로(AGENTS "stopHlsEgress 는 방 단위 일괄") 레코드 수는 방 수의 배수가 되고, 그러면
        // 방 수 단위인 live.room.active 와 나란히 못 놓는다. 방치 Live 잡도 같은 방식으로 센다.
        liveMetrics.liveKitActiveEgressRooms((int) egresses.stream()
                .filter(EgressSummary::active)
                .map(egress -> LiveKitRoomNames.parseRoomId(egress.roomName()))
                .filter(Objects::nonNull)
                .distinct()
                .count());

        reconcileMedia(ingresses, egresses, threshold);

        // 방 스윕은 뒤에, 그리고 조회도 여기서 한다 — 앞에서 같이 조회하면 방 조회 하나가 실패했을 때
        // 이미 받아둔 ingress/egress 조차 처리하지 못하고 회차가 죽는다. 그쪽이 과금이 이어지는 방향이라
        // 먼저 끝내둔다. 방 조회 실패는 그대로 올려 회차를 실패로 남긴다(빈 목록으로 삼키면 안 되므로).
        reconcileSfuRooms(liveMediaManager.listAllRooms(), threshold);

        // 세 스윕을 모두 마친 뒤에만 완료로 센다 — 중간에 예외로 빠지면 완료 카운터가 오르지 않고,
        // "돌기는 도는데 늘 중간에 죽는" 상태가 completed 와 스케줄 주기의 차이로 드러난다.
        liveMetrics.reconcileRoundCompleted(ReconcileJob.ORPHAN_MEDIA,
                Duration.between(startedAt, timeProvider.now()));
    }

    private void reconcileMedia(
            List<IngressSummary> ingresses, List<EgressSummary> egresses, Instant threshold) {
        Map<UUID, LiveRoom> rooms = loadRooms(Stream.concat(
                ingresses.stream().map(IngressSummary::roomName),
                egresses.stream().map(EgressSummary::roomName)));

        reconcileIngresses(ingresses, rooms, threshold);
        reconcileEgresses(egresses, rooms, threshold);
    }

    private void reconcileSfuRooms(List<RoomSummary> sfuRooms, Instant threshold) {
        reconcileRooms(sfuRooms, loadRooms(sfuRooms.stream().map(RoomSummary::roomName)), threshold);
    }

    /**
     * DB 는 종료된 방인데 LiveKit 에는 살아 있는 SFU 방을 닫는다.
     *
     * <p>이 잡이 없으면 회수 주체가 없다. 판매자 토큰은 TTL 6시간에 폐기 수단이 없어, 종료 시
     * {@code closeRoom} 으로 방을 지워도 그 토큰으로 다시 join 하면 LiveKit 이 방을 되살린다. 되살아난
     * 방은 <b>ingress 도 egress 도 만들지 않으므로</b> 위 두 정리 경로에 잡히지 않는다.
     *
     * <p>참가자 수는 <b>판정에 쓰지 않는다</b> — DB 가 Ended 면 빈 방이든 아니든 있어선 안 된다. 빈 방만
     * 지우면 "판매자가 되돌아온" 경우(정확히 이 잡의 표적)를 놓친다. 대신 로그에 실어 "종료 정리가
     * 실패해 남은 빈 방"과 구분할 수 있게 한다.
     *
     * <p>Ended 가 아닌 방은 손대지 않는다. 예약(Scheduled) 상태에서 {@code createRoom} 만 끝나고 아직
     * 커밋되지 않은 방이 여기 걸리면 시작하려는 방송을 끊는다.
     */
    private void reconcileRooms(List<RoomSummary> sfuRooms, Map<UUID, LiveRoom> rooms, Instant threshold) {
        int closed = 0;
        // 집계는 finally 로 — 스윕 도중 예외로 빠지면 그때까지 요청한 건수가 통째로 사라진다.
        // 다른 두 정리 잡과 같은 이유·같은 모양이다.
        try {
        for (RoomSummary sfuRoom : sfuRooms) {
            UUID roomId = LiveKitRoomNames.parseRoomId(sfuRoom.roomName());
            if (roomId == null) {
                continue; // 우리 방 이름 규칙이 아님 — 남의 리소스일 수 있으니 손대지 않는다
            }
            LiveRoom room = rooms.get(roomId);
            if (room == null) {
                log.warn("DB 에 없는 SFU 방 — 닫지 않음. roomName={}", sfuRoom.roomName());
                continue;
            }
            if (!(room.status() instanceof LiveStatus.Ended)) {
                continue;
            }
            // <b>여기에는 유예를 두지 않는다.</b> 위 가드 때문에 이 지점에 오는 건 Ended 방뿐인데, Ended 방에
            // 정당한 SFU 방은 존재할 수 없다(시작은 Scheduled 에서만 하고 그 시점의 DB 는 Ended 가 아니다).
            // 즉 유예는 보호하는 게 없으면서 구멍을 만든다: 판매자가 토큰으로 재입장하면 방이 <b>새 생성
            // 시각으로 다시 생기므로</b>, 유예보다 짧은 주기로 재입장하면 이 잡이 영원히 발화하지 않는다.
            // 그게 바로 이 스윕을 만든 시나리오다. createdAt 은 이제 판정이 아니라 로그용이다.
            liveMediaManager.closeRoom(roomId.toString());
            closed++;
            log.warn("종료된 방의 SFU 방 회수 — 판매자 토큰 재입장 또는 종료 정리 실패. roomId={}, 참가자={}, 방 생성={}",
                    roomId, sfuRoom.participants(), sfuRoom.createdAt());
        }
        } finally {
            liveMetrics.reconcileActed(
                    ReconcileJob.ORPHAN_MEDIA, ReconcileAction.SFU_ROOM_CLOSE_REQUESTED, closed);
        }
        log.info("고아 SFU 방 정리 완료. 전체={}, 닫음={}", sfuRooms.size(), closed);
    }

    /** 목록에 등장한 방을 한 번에 읽는다(방마다 조회하면 LiveKit 리소스 수만큼 쿼리가 나간다). */
    private Map<UUID, LiveRoom> loadRooms(Stream<String> roomNames) {
        Set<UUID> roomIds = roomNames
                .map(LiveKitRoomNames::parseRoomId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (roomIds.isEmpty()) {
            return Map.of(); // 스윕이 둘로 나뉘어 한쪽 목록이 비는 게 정상이다 — 빈 조회를 날리지 않는다
        }
        return liveRoomRepository.findAllByIds(roomIds).stream()
                .collect(Collectors.toMap(LiveRoom::id, Function.identity()));
    }

    private void reconcileIngresses(List<IngressSummary> ingresses, Map<UUID, LiveRoom> rooms, Instant threshold) {
        int deleted = 0;
        try {
        for (IngressSummary ingress : ingresses) {
            UUID roomId = LiveKitRoomNames.parseRoomId(ingress.roomName());
            if (roomId == null) {
                continue; // 우리 방 이름 규칙이 아님 — 남의 리소스일 수 있으니 손대지 않는다
            }
            LiveRoom room = rooms.get(roomId);
            if (room == null) {
                log.warn("DB 에 없는 ingress — 삭제하지 않음. roomName={}, ingressId={}",
                        ingress.roomName(), ingress.ingressId());
                continue;
            }
            // 송출 중이어도 보호받는 건 "방이 인정하는 ingress" 뿐이다. 그 외(종료된 방의 잔재, 경합
            // 패자의 회수 실패분)는 송출 중이라도 회수 대상이다 — 판매자는 이미 받은 streamKey 로 계속
            // 송출할 수 있는데, 여기서 건너뛰면 아무도 회수하지 않아 egress 과금이 이어지고 살아남은
            // ingress 가 닫힌 SFU 방을 재생성한다(좀비 방).
            //
            // publishing 만으로 보호하면 Ready 방이 영구 고착한다: 그 방을 만료 배치도 손대지 못하고
            // (방이 인정 안 한 ingress 가 송출 중이라 승격도 만료도 못 함) 여기서도 건너뛰어, 방이
            // Ended 가 될 경로 자체가 사라진다.
            if (ingress.publishing() && !isOrphanIngress(room, ingress.ingressId())) {
                continue;
            }
            // ingress 는 LiveKit 이 생성 시각을 주지 않아 방의 updated_at 으로 나이를 잰다.
            // 그래서 이 유예는 "방금 만든 ingress" 를 지켜주지 못한다: PrepareIngressService 는 createIngress
            // 를 트랜잭션 밖에서 부르고 updated_at 은 그 뒤 조건부 UPDATE 에서야 갱신되므로, 그 사이(ms 단위)
            // 에는 갓 만든 ingress 인데 방의 updated_at 은 예약 시각이라 유예가 이미 지난 것으로 읽힌다.
            // 고치려면 외부 호출 전에 쓰기 트랜잭션을 한 번 더 열어야 해서(= "외부 호출은 트랜잭션 밖" 결정과
            // 충돌) ms 창을 막자고 지불하기엔 비싸다. 회수돼도 판매자가 재발급받으면 되는 범위라 남겨둔다.
            // Ended 방에는 유예를 적용하지 않는다. 유예는 "방금 만든 정상 ingress" 를 지키는 장치인데
            // canPrepareIngress() 가 Scheduled 만 허용하므로 Ended 방에 갓 만든 ingress 는 존재할 수 없다.
            // 그대로 두면 종료 커밋이 updated_at 을 갱신하기 때문에, 커밋~afterCommit 사이에 파드가 죽어
            // 살아남은 ingress 가 유예 + 다음 회차만큼 회수되지 않고 판매자는 그동안 계속 push 할 수 있다.
            boolean ended = room.status() instanceof LiveStatus.Ended;
            if (!ended && (room.updatedAt() == null || room.updatedAt().isAfter(threshold))) {
                continue;
            }
            if (!isOrphanIngress(room, ingress.ingressId())) {
                continue;
            }
            liveMediaManager.deleteIngress(roomId, ingress.ingressId());
            deleted++;
        }
        } finally {
            liveMetrics.reconcileActed(
                    ReconcileJob.ORPHAN_MEDIA, ReconcileAction.INGRESS_DELETE_REQUESTED, deleted);
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
            UUID roomId = LiveKitRoomNames.parseRoomId(egress.roomName());
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
        // 형제 스윕(ingress/room)과 같이 <b>실제로 요청한 건수</b>를 센다. 대상 수(roomsToStop.size())를
        // 쓰면 중간에 예외로 빠졌을 때 요청조차 안 한 방까지 계상돼, "같은 수치가 반복되면 안 치워지는
        // 중" 이라는 판독법이 흐려진다.
        int requested = 0;
        try {
            for (UUID roomId : roomsToStop) {
                liveMediaManager.stopHlsEgress(roomId);
                requested++;
            }
        } finally {
            liveMetrics.reconcileActed(
                    ReconcileJob.ORPHAN_MEDIA, ReconcileAction.EGRESS_STOP_REQUESTED, requested);
        }
        log.info("고아 egress 정리 완료. 전체={}, 중단 요청={}", egresses.size(), requested);
    }

}
