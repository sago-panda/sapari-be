package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
 *
 * <h2>순회 단위는 <b>방</b>이다 (리소스 종류가 아니라)</h2>
 *
 * <p>예전에는 ingress·egress·SFU 방을 <b>종류별로 따로</b> 스윕했고, 각 스윕이 자기 batch-size 상한과
 * 자기 회전 창을 가졌다. 그 구조에서는 지켜야 할 불변식을 <b>구조적으로 지킬 수가 없었다</b>:
 *
 * <ul>
 *   <li>AGENTS "ingress 를 전부 지운 뒤에 closeRoom" 은 <b>방 단위</b> 규칙인데, 상한·창이 종류별로
 *       걸리니 같은 방의 ingress 삭제만 다음 회차로 밀리고 방은 닫히는 조합이 나왔다 — 살아남은
 *       ingress 로 OBS 가 닫힌 방을 되살린다(좀비 방 + egress 과금 지속).</li>
 *   <li>"Ended 방 우선" 정렬을 해도 회전 창이 그 위에 얹혀 선두 창 방문이 확률이 됐다.</li>
 *   <li>정지 규칙이 셋(상한·창·마감)이라 서로 간섭했다.</li>
 * </ul>
 *
 * <p>그래서 방으로 묶어 <b>한 방의 리소스를 한자리에서</b> 정해진 순서로 처리한다. 정지 규칙은
 * {@link OrphanMediaReconcilePolicy#roundBudget() 마감} 하나뿐이다 — 상한과 회전은 삭제했다.
 * 회차 비용을 미리 계산해 상한을 잡으려던 게 애초에 불가능했고(방당 리소스 수가 설정으로 는다),
 * 그래서 마감을 넣은 순간 상한·회전은 존재 이유를 잃었다.
 *
 * <p><b>회전을 두지 않는 이유</b>(기아가 불가능해서가 아니다): 정지 규칙이 마감 하나라 적체가 한 회차
 * 처리량보다 크면 <b>꼬리는 여전히 밀린다</b>. 후보 순서도 결정적이다(Ended → updated_at → roomId).
 * 회전은 그걸 못 고친다 — 누가 기다릴지 순서만 바꿀 뿐 처리량을 늘리지 않고, 정렬 위에 창을 얹는 것이
 * 창 기아 결함들이 나온 자리였다. 매 회차 전수 조회를 하므로 처리된 리소스는 다음 회차 목록에서
 * 사라지고, 남는 건 실패했거나 예산이 모자란 것이다 — 그건 {@code ORPHAN_ROOM_CANDIDATE} 가 회차를
 * 거쳐도 줄지 않는 것으로 드러나야 할 신호이지 회전으로 가릴 게 아니다.
 *
 * <p>리소스별 판정 술어({@link #isOrphanIngress}, egress 나이·활성·상태 조건, Ended 방만 닫기)는
 * 예전 그대로다 — 바뀐 건 순회뿐이다.
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
        // 유일한 정지 규칙. 후보당 왕복 수가 방의 리소스 수에 비례해 고정이 아니므로 개수로는 회차를
        // 리스 안에 묶을 수 없다. 세 잡 중 DB 게이트가 없는 유일한 잡이라 리스를 넘겨 겹쳐 도는
        // 대가가 가장 크다(파괴 호출이 두 번 나가고, 포트가 삼켜 예외로도 안 드러난다).
        Instant deadline = startedAt.plus(policy.roundBudget());

        // 조회 실패는 예외로 올라온다(빈 목록이면 "고아 없음"으로 오독되므로).
        List<IngressSummary> allIngresses = liveMediaManager.listAllIngress();
        List<EgressSummary> allEgresses = liveMediaManager.listAllEgress();

        // 방 목록만 실패를 <b>미뤄서</b> 던진다. 미디어 회수는 과금이 이어지는 방향이라, 방 조회 하나가
        // 실패했다고 이미 받아둔 ingress/egress 까지 손대지 못한 채 회차가 죽으면 안 된다. 삼키는 게
        // 아니라 미디어를 끝낸 뒤 그대로 다시 던져 회차를 실패로 남긴다(빈 목록으로 대체하면
        // "SFU 방 없음"으로 오독된다).
        //
        // 이때 SFU 방 닫기는 <b>한 건도</b> 하지 않는다. 목록이 없으면 어느 방이 LiveKit 에 살아 있는지
        // 모르고, 모르는 채 닫지 않는 건 안전한 방향이다(순서 불변식도 자동으로 지켜진다).
        List<RoomSummary> allSfuRooms = List.of();
        RuntimeException roomListFailure = null;
        try {
            allSfuRooms = liveMediaManager.listAllRooms();
        } catch (RuntimeException e) {
            roomListFailure = e;
            log.error("SFU 방 목록 조회 실패 — 이번 회차는 방을 닫지 않고 미디어만 회수한다", e);
        }

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
        // 띄우므로 레코드 수는 방 수의 배수가 되고, 그러면 방 수 단위인 live.room.active 와 나란히
        // 못 놓는다. 방치 Live 잡도 같은 방식으로 센다.
        liveMetrics.liveKitActiveEgressRooms((int) allEgresses.stream()
                .filter(EgressSummary::active)
                .map(egress -> LiveKitRoomNames.parseRoomId(egress.roomName()))
                .filter(Objects::nonNull)
                .distinct()
                .count());

        try {
            reconcileByRoom(allIngresses, allEgresses, allSfuRooms, threshold, deadline);
        } catch (RuntimeException e) {
            if (roomListFailure != null) {
                // 스윕이 따로 죽으면 그쪽을 올리되 방 목록 실패도 함께 실어 보낸다 — 로그 한 줄로만
                // 남기면 "왜 이 회차엔 방을 하나도 안 닫았나" 를 사후에 되짚을 근거가 사라진다.
                e.addSuppressed(roomListFailure);
            }
            throw e;
        }
        if (roomListFailure != null) {
            throw roomListFailure; // 미디어는 끝냈다. 회차는 실패로 남긴다.
        }

        // 스윕을 마친 뒤에만 완료로 센다 — 중간에 예외로 빠지면 완료 카운터가 오르지 않고,
        // "돌기는 도는데 늘 중간에 죽는" 상태가 completed 와 스케줄 주기의 차이로 드러난다.
        liveMetrics.reconcileRoundCompleted(ReconcileJob.ORPHAN_MEDIA,
                Duration.between(startedAt, timeProvider.now()));
    }

    /**
     * 방 하나를 한자리에서 처리한다. <b>순서를 바꾸지 말 것</b>: ingress 삭제 → egress 중단 → 방 닫기.
     * {@code PostCommitMediaCleanup} 의 종료 정리와 같은 순서이고 같은 이유다 — ingress 가 남은 채
     * 방을 닫으면 OBS 자동 재접속이 방을 되살린다.
     */
    private void reconcileByRoom(List<IngressSummary> ingresses, List<EgressSummary> egresses,
            List<RoomSummary> sfuRooms, Instant threshold, Instant deadline) {

        Map<UUID, RoomResources> byRoom = groupByRoom(ingresses, egresses, sfuRooms);
        // DB 조회 <b>전에</b> 손댈 수 없는 방을 떨군다. listAllEgress 는 서버측 activeOnly 를 일부러
        // 쓰지 않아(어댑터 주석 참고) 종료된 egress 이력이 그대로 실려 오는데, 그 이력만 있는 방은
        // DB 상태가 무엇이든 이번 회차에 할 일이 없다. 걸러내지 않으면 IN 절이 이력 누적만큼 커진다.
        Set<UUID> worthLoading = byRoom.entrySet().stream()
                .filter(entry -> couldHaveWork(entry.getValue(), threshold))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, LiveRoom> snapshots = loadRooms(worthLoading);

        // 오래된 방부터. Ended 방을 앞세우는 건 그 방의 리소스가 전부 확실한 고아이기 때문이다
        // (Ended 가 아닌 방에서는 경합 패자 ingress 하나만 고아다). 회전이 없으니 이 정렬이
        // 실제 처리 순서 그대로다 — 예전에는 창이 이 위에 얹혀 선두 방문이 확률이었다.
        List<UUID> candidates = worthLoading.stream()
                .filter(roomId -> hasAnythingToDo(roomId, byRoom.get(roomId), snapshots.get(roomId), threshold))
                .sorted(Comparator
                        .comparing((UUID roomId) ->
                                !(snapshots.get(roomId).status() instanceof LiveStatus.Ended))
                        .thenComparing(roomId -> snapshots.get(roomId).updatedAt(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(UUID::toString))
                .toList();

        int ingressDeletes = 0;
        int egressStops = 0;
        int roomCloses = 0;
        int roomsHandled = 0;
        boolean budgetExhausted = false;
        // 집계는 finally 로 — 스윕 도중 예외로 빠지면 그때까지 요청한 건수가 통째로 사라진다.
        try {
            for (UUID roomId : candidates) {
                if (!timeProvider.now().isBefore(deadline)) {
                    budgetExhausted = true;
                    log.warn("고아 미디어 정리 예산 소진 — 남은 방은 다음 회차로. 처리한 방={}, 남은 방={}",
                            roomsHandled, candidates.size() - roomsHandled);
                    break;
                }
                // <b>만지기 직전에</b> 방을 다시 읽는다(AGENTS "per room, right before touching it").
                // Ended 는 종단 상태라 되돌아오지 않으므로 스냅샷을 그대로 믿는다 — 그 방만 조회를 아낀다.
                LiveRoom snapshot = snapshots.get(roomId);
                LiveRoom current = snapshot.status() instanceof LiveStatus.Ended
                        ? snapshot : liveRoomRepository.findById(roomId).orElse(null);
                if (current == null) {
                    continue; // 조회 사이에 사라진 방 — 판정 불가는 건드리지 않는다
                }
                RoomResources resources = byRoom.get(roomId);

                // 마감에 걸려 이 방의 정리를 다 못 끝냈다는 표시. 걸리면 방을 닫지 않는다(아래 3).
                boolean cleanupPending = false;

                // 1) ingress — 방이 인정하지 않는 것 전부. 개수 상한은 두지 않는다(하나라도 남기고
                //    방을 닫으면 그 생존자가 방을 되살린다). 대신 <b>마감</b>은 여기서도 본다 —
                //    경합 패자가 쌓인 방은 ingress 가 수십 건일 수 있고, 그때 이 루프만 무제한이면
                //    "리스 ≥ 예산 + 방 하나의 fan-out" 보장이 이름뿐이 된다.
                int deletedHere = 0;
                for (IngressSummary ingress : resources.ingresses()) {
                    if (!ingressGraceElapsed(current, threshold)
                            || !isOrphanIngress(current, ingress.ingressId())) {
                        continue;
                    }
                    if (!timeProvider.now().isBefore(deadline)) {
                        budgetExhausted = true;
                        cleanupPending = true;
                        log.warn("고아 미디어 정리 예산 소진(ingress 처리 중) — 남은 ingress 는 다음 회차로."
                                + " roomId={}, 이번에 삭제={}", roomId, deletedHere);
                        break;
                    }
                    liveMediaManager.deleteIngress(roomId, ingress.ingressId());
                    ingressDeletes++;
                    deletedHere++;
                }

                // 2) egress — 스냅샷에서 판정한 id 만 개별 중단한다. 방 단위 stop API 를 쓰면 그 사이
                //    재시작된 새 방송의 egress 까지 끊는다.
                //
                //    <b>방의 updated_at 유예를 걸지 않는다</b> — egress 는 자기 startedAt 나이로 판정한다.
                //    유예는 "방금 만들어져 아직 DB 에 안 실린 리소스"를 지키려는 것인데, egress 는 그
                //    자신이 언제 시작했는지를 들고 있어 그 판정이 직접 가능하다. 방 기준으로 걸면
                //    Scheduled/Ready 에서 updated_at 이 계속 갱신되는 방의 고아 egress 가 영영 회수되지
                //    않고 과금만 이어진다(예전 종류별 스윕도 여기에는 유예를 걸지 않았다).
                if (!cleanupPending && !(current.status() instanceof LiveStatus.Live)) {
                    for (EgressSummary egress : resources.egresses()) {
                        if (!isOrphanEgress(egress, threshold)) {
                            continue;
                        }
                        // 방 안에서도 마감을 본다. <b>상한이 아니라 마감</b>인 게 핵심이다 — 상한은
                        // 정적이라 같은 egress 가 매 회차 같은 자리에서 잘리지만, 마감은 그 회차만
                        // 미루고 남은 egress 는 다음 회차 전수 조회에 그대로 다시 잡힌다.
                        if (!timeProvider.now().isBefore(deadline)) {
                            budgetExhausted = true;
                            cleanupPending = true;
                            log.warn("고아 미디어 정리 예산 소진(egress 처리 중) — 남은 egress 는 다음 회차로."
                                    + " roomId={}", roomId);
                            break;
                        }
                        liveMediaManager.stopEgress(roomId, egress.egressId());
                        egressStops++;
                    }
                }

                // 3) 방 닫기 — Ended 인 방만. <b>이 방의 정리가 끝났을 때만</b> 닫는다: 마감에 걸려
                //    ingress 나 egress 를 남겼으면 방을 열어 둔 채 다음 회차로 넘긴다. 예전 구조는 이 조건을
                //    표현할 수 없었고(스윕이 달랐다), 그래서 "ingress 는 밀렸는데 방은 닫힘" 이 났다.
                // 삭제 포트는 <b>실패를 삼킨다</b>(비-2xx·예외 모두). 그래서 "지웠다고 요청했다"와
                // "지워졌다"가 다르고, 전부 실패해도 여기까지 온다 — 그대로 닫으면 살아남은 ingress 로
                // OBS 가 방을 되살린다(실패 경로에서만 불변식이 깨지는 모양이라 더 안 보인다).
                // 그래서 닫기 직전에 <b>한 번 더 물어본다</b>. listRoomIngress 는 조회 실패를 예외로
                // 올리는 쪽이라(파괴 판단의 입력이므로) 여기서는 "모르면 닫지 않는다"로 번역한다.
                //
                // <b>"이번에 지운 게 있을 때만" 으로 좁히면 안 된다.</b> 그 조건은 회차 시작 스냅샷에
                // 다시 기대는 건데, 재확인의 취지가 바로 그 목록을 믿지 않는 것이다. 스냅샷은 최대
                // 예산만큼(기본 16분) 낡았고, 그 사이 생긴 ingress 는 목록에 없어 삭제 시도조차 안 된다.
                // 닫기 단계도 마감 안이어야 한다. 여기까지 오면 남은 왕복이 재확인 1 + closeRoom 1
                // 이라 최대 30s 인데, 리스 여유는 리스의 1/5 뿐이고 그 여유가 얼마인지는 설정에 달렸다
                // (lock-at-most-for=PT10M 이면 2분). "리스 ≥ 예산 + 방 하나의 fan-out" 을 어디서도
                // 강제하지 못하므로, 강제 대신 여기서 멈춘다 — 남은 방은 다음 회차가 가져간다.
                if (!cleanupPending && resources.hasSfuRoom()
                        && current.status() instanceof LiveStatus.Ended
                        && !timeProvider.now().isBefore(deadline)) {
                    budgetExhausted = true;
                    cleanupPending = true;
                    log.warn("고아 미디어 정리 예산 소진(방 닫기 직전) — 이 방은 다음 회차로. roomId={}", roomId);
                }

                if (!cleanupPending && resources.hasSfuRoom()
                        && current.status() instanceof LiveStatus.Ended) {
                    try {
                        List<IngressSummary> survivors = liveMediaManager.listRoomIngress(roomId);
                        if (!survivors.isEmpty()) {
                            cleanupPending = true;
                            log.warn("ingress 삭제가 반영되지 않았다 — 방을 닫지 않고 다음 회차로."
                                    + " roomId={}, 잔존={}", roomId, survivors.size());
                        }
                    } catch (RuntimeException e) {
                        cleanupPending = true;
                        log.warn("ingress 잔존 확인 실패 — 방을 닫지 않고 다음 회차로. roomId={}", roomId, e);
                    }
                }

                if (resources.hasSfuRoom() && current.status() instanceof LiveStatus.Ended && !cleanupPending) {
                    // <b>유예를 두지 않는다.</b> Ended 방에 정당한 SFU 방은 존재할 수 없다(시작은
                    // Scheduled 에서만 하고 그 시점의 DB 는 Ended 가 아니다). 유예는 보호하는 게 없으면서
                    // 구멍을 만든다: 판매자가 토큰으로 재입장하면 방이 새 생성 시각으로 다시 생기므로,
                    // 유예보다 짧은 주기로 재입장하면 이 잡이 영원히 발화하지 않는다 — 그게 이 경로를
                    // 만든 시나리오다. 참가자 수도 판정에 쓰지 않고 로그에만 싣는다.
                    liveMediaManager.closeRoom(roomId.toString());
                    roomCloses++;
                    log.warn("종료된 방의 SFU 방 회수 — 판매자 토큰 재입장 또는 종료 정리 실패."
                                    + " roomId={}, 참가자={}, ingress 삭제={}",
                            roomId, resources.participants(), deletedHere);
                }
                if (cleanupPending) {
                    // 예산에 걸려 이 방의 정리를 다 못 끝냈다 — "처리함" 으로 세면 완료 로그의
                    // 처리/남음 숫자가 실제와 어긋난다.
                    break;
                }
                roomsHandled++;
            }
        } finally {
            liveMetrics.reconcileActed(
                    ReconcileJob.ORPHAN_MEDIA, ReconcileAction.INGRESS_DELETE_REQUESTED, ingressDeletes);
            liveMetrics.reconcileActed(
                    ReconcileJob.ORPHAN_MEDIA, ReconcileAction.EGRESS_STOP_REQUESTED, egressStops);
            liveMetrics.reconcileActed(
                    ReconcileJob.ORPHAN_MEDIA, ReconcileAction.SFU_ROOM_CLOSE_REQUESTED, roomCloses);
            liveMetrics.reconcileActed(
                    ReconcileJob.ORPHAN_MEDIA, ReconcileAction.ORPHAN_ROOM_CANDIDATE, candidates.size());
            liveMetrics.reconcileActed(ReconcileJob.ORPHAN_MEDIA,
                    ReconcileAction.ROUND_BUDGET_EXHAUSTED, budgetExhausted ? 1 : 0);
        }
        log.info("고아 미디어 정리 완료. 후보 방={}, 처리={}, ingress 삭제={}, egress 중단={}, 방 닫음={}",
                candidates.size(), roomsHandled, ingressDeletes, egressStops, roomCloses);
    }

    /** LiveKit 의 세 목록을 방 단위로 접는다. 우리 이름 규칙이 아닌 리소스는 남의 것일 수 있어 버린다. */
    private Map<UUID, RoomResources> groupByRoom(List<IngressSummary> ingresses,
            List<EgressSummary> egresses, List<RoomSummary> sfuRooms) {
        Map<UUID, RoomResources> byRoom = new LinkedHashMap<>();
        for (IngressSummary ingress : ingresses) {
            UUID roomId = LiveKitRoomNames.parseRoomId(ingress.roomName());
            if (roomId != null) {
                byRoom.computeIfAbsent(roomId, ignored -> new RoomResources()).ingresses().add(ingress);
            }
        }
        for (EgressSummary egress : egresses) {
            UUID roomId = LiveKitRoomNames.parseRoomId(egress.roomName());
            if (roomId != null) {
                byRoom.computeIfAbsent(roomId, ignored -> new RoomResources()).egresses().add(egress);
            }
        }
        for (RoomSummary sfuRoom : sfuRooms) {
            UUID roomId = LiveKitRoomNames.parseRoomId(sfuRoom.roomName());
            if (roomId != null) {
                byRoom.computeIfAbsent(roomId, ignored -> new RoomResources()).setSfuRoom(sfuRoom);
            }
        }
        // egress 는 시작이 이른 것부터(오래 켜져 있던 것이 과금도 크다). ingress 는 id 로 안정 정렬만.
        byRoom.values().forEach(resources -> {
            resources.egresses().sort(Comparator.comparing(EgressSummary::startedAt,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(EgressSummary::egressId));
            resources.ingresses().sort(Comparator.comparing(IngressSummary::ingressId));
        });
        return byRoom;
    }

    /**
     * DB 상태와 무관하게 이번 회차에 할 일이 <b>있을 수도</b> 있는 방인가. DB 조회 대상을 좁히는 용도라
     * 여기서는 방 상태를 보지 않는다 — 최종 판정은 {@link #hasAnythingToDo} 가 스냅샷을 받고 한다.
     */
    private boolean couldHaveWork(RoomResources resources, Instant threshold) {
        return !resources.ingresses().isEmpty()
                || resources.hasSfuRoom()
                || resources.egresses().stream().anyMatch(egress -> isOrphanEgress(egress, threshold));
    }

    /** 방에 등장한 DB 행을 한 번에 읽는다(방마다 조회하면 LiveKit 리소스 수만큼 쿼리가 나간다). */
    private Map<UUID, LiveRoom> loadRooms(Set<UUID> roomIds) {
        if (roomIds.isEmpty()) {
            return Map.of();
        }
        return liveRoomRepository.findAllByIds(new LinkedHashSet<>(roomIds)).stream()
                .collect(Collectors.toMap(LiveRoom::id, Function.identity()));
    }

    /**
     * 이 방에 이번 회차가 손댈 것이 하나라도 있는가. 없으면 후보에서 빼 <b>DB 재조회조차 하지 않는다</b>.
     *
     * <p>DB 에 행이 없는 방은 여기서 걸러진다 — 로그만 남기고 두는 게 이 잡의 원칙이다
     * ({@code createIngress} 는 끝났고 {@code save} 는 아직인 정상 요청이 정확히 그렇게 보인다).
     */
    private boolean hasAnythingToDo(UUID roomId, RoomResources resources, LiveRoom snapshot, Instant threshold) {
        if (snapshot == null) {
            // roomId 를 반드시 싣는다 — 일부러 아무것도 안 하는 분기라, 이 로그가 사후 추적의
            // 유일한 단서다(어느 방인지 없으면 "뭔가 남아 있다"는 사실만 남는다).
            log.warn("DB 에 없는 LiveKit 리소스 — 건드리지 않음. roomId={}, ingress={}, egress={}, sfu방={}",
                    roomId, resources.ingresses().size(), resources.egresses().size(), resources.hasSfuRoom());
            return false;
        }
        boolean ended = snapshot.status() instanceof LiveStatus.Ended;
        boolean orphanIngress = ingressGraceElapsed(snapshot, threshold)
                && resources.ingresses().stream()
                .anyMatch(ingress -> isOrphanIngress(snapshot, ingress.ingressId()));
        boolean orphanEgress = !(snapshot.status() instanceof LiveStatus.Live)
                && resources.egresses().stream().anyMatch(egress -> isOrphanEgress(egress, threshold));
        return orphanIngress || orphanEgress || (resources.hasSfuRoom() && ended);
    }

    /**
     * ingress 삭제에만 거는 유예. 방금 {@code createIngress} 를 마치고 {@code save} 가 아직인 정상
     * 요청이 고아와 똑같이 보이므로, 방의 {@code updated_at} 이 유예를 지났을 때만 손댄다.
     * Ended 방에는 유예가 없다 — 그 상태에 정당한 ingress 는 존재하지 않는다.
     *
     * <p>egress 에는 이 게이트를 쓰지 않는다({@link #isOrphanEgress} 참고).
     */
    private boolean ingressGraceElapsed(LiveRoom room, Instant threshold) {
        if (room.status() instanceof LiveStatus.Ended) {
            return true;
        }
        return room.updatedAt() != null && !room.updatedAt().isAfter(threshold);
    }

    /** 방이 인정하는 ingress 는 "종료되지 않은 방의 ingress_id" 하나뿐. 그 외는 전부 고아다. */
    private boolean isOrphanIngress(LiveRoom room, String ingressId) {
        if (room.status() instanceof LiveStatus.Ended) {
            return true;
        }
        return !(room.streamType() instanceof LiveStreamType.Rtmp rtmp)
                || !rtmp.ingressId().equals(ingressId);
    }

    /**
     * 이미 멈춘 egress 는 비용이 안 나가므로 대상이 아니고, 시작 시각을 모르면 나이를 알 수 없어
     * 판정 불가다 — 판정 불가는 건드리지 않는다. 방 상태(Live 면 정상)는 호출자가 본다.
     */
    private boolean isOrphanEgress(EgressSummary egress, Instant threshold) {
        return egress.active()
                && egress.startedAt() != null
                && !egress.startedAt().isAfter(threshold);
    }

    /** 한 방에 묶인 LiveKit 리소스. 방 단위 순회의 작업 단위다. */
    private static final class RoomResources {
        private final List<IngressSummary> ingresses = new ArrayList<>();
        private final List<EgressSummary> egresses = new ArrayList<>();
        private RoomSummary sfuRoom;

        private List<IngressSummary> ingresses() {
            return ingresses;
        }

        private List<EgressSummary> egresses() {
            return egresses;
        }

        private void setSfuRoom(RoomSummary sfuRoom) {
            this.sfuRoom = sfuRoom;
        }

        private boolean hasSfuRoom() {
            return sfuRoom != null;
        }

        private Integer participants() {
            return sfuRoom == null ? null : sfuRoom.participants();
        }
    }
}
