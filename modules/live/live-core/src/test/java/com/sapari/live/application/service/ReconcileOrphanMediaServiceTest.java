package com.sapari.live.application.service;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.ReconcileJob;
import com.sapari.live.application.port.RecordingLiveMetrics;
import com.sapari.live.application.port.EgressSummary;
import com.sapari.live.application.port.IngressSummary;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.application.port.OrphanMediaReconcilePolicy;
import com.sapari.live.application.port.RoomSummary;
import com.sapari.live.domain.exception.LiveMediaException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.model.StreamInfo;
import com.sapari.live.domain.repository.LiveRoomRepository;

/**
 * 고아 미디어 회수 판정.
 *
 * <p>이 서비스의 오판은 <b>살아 있는 방송을 끊는다</b>. 그래서 "지운다" 못지않게 "지우지 않는다" 케이스를
 * 촘촘히 고정한다 — 판정이 애매하면 남겨두는 게 계약이다.
 */
@ExtendWith(MockitoExtension.class)
class ReconcileOrphanMediaServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-10T12:00:00Z");
    /** NOW - grace(15m). 이보다 오래된 것만 회수 대상. */
    private static final Instant OLD = Instant.parse("2026-06-10T11:00:00Z");
    private static final Instant RECENT = Instant.parse("2026-06-10T11:55:00Z");

    @Mock
    private LiveMediaManager liveMediaManager;

    @Mock
    private LiveRoomRepository liveRoomRepository;

    @Mock
    private TimeProvider timeProvider;

    private final RecordingLiveMetrics liveMetrics = new RecordingLiveMetrics();

    private ReconcileOrphanMediaService service;

    private UUID roomId;

    @BeforeEach
    void setup() {
        service = new ReconcileOrphanMediaService(
                liveMediaManager, liveRoomRepository,
                new OrphanMediaReconcilePolicy(Duration.ofMinutes(15), Duration.ofMinutes(16)), timeProvider, liveMetrics);
        roomId = UUID.randomUUID();
    }

    /**
     * 개수 상한이 없다는 계약. 예전에는 종류별 batch-size 로 끊었는데, 그 상한이 있는 한 같은 방의
     * ingress 삭제만 밀리고 방은 닫히는 조합을 막을 수 없었다(살아남은 ingress → OBS 가 방 재생성).
     * 지금 정지 규칙은 마감 하나뿐이라, 예산 안이면 후보 방을 전부 처리한다.
     */
    @Test
    @DisplayName("적체가 많아도 예산 안이면 한 회차에 후보 방을 전부 처리한다 — 개수 상한이 없다")
    void backlogOverOldCap_isFullyProcessedWithinBudget() {
        List<UUID> ids = givenElevenEndedRoomsWithEveryResourceKind();

        service.reconcile();

        then(liveMediaManager).should(times(11)).deleteIngress(any(UUID.class), anyString());
        then(liveMediaManager).should(times(11)).stopEgress(any(UUID.class), anyString());
        then(liveMediaManager).should(times(11)).closeRoom(anyString());
        org.assertj.core.api.Assertions.assertThat(liveMetrics.acted)
                .contains("ORPHAN_ROOM_CANDIDATE=11", "ROUND_BUDGET_EXHAUSTED=0");
    }

    /**
     * 이 잡이 스스로 좀비 방을 만들지 않는다는 계약 — AGENTS 의 "ingress 를 전부 지운 뒤 closeRoom".
     * 예전 구조는 두 스윕이 각자 상한·창을 가져 이 순서를 표현할 수 없었다.
     */
    @Test
    @DisplayName("방을 닫기 전에 그 방의 고아 ingress 삭제가 먼저 나간다 — 생존자가 방을 되살린다")
    void closeRoom_neverPrecedesItsOwnIngressDeletes() {
        List<UUID> ids = givenElevenEndedRoomsWithEveryResourceKind();

        service.reconcile();

        // 방 사이의 순서는 정렬(Ended 우선 → updatedAt → roomId)이 정하므로 그 순서로 확인한다.
        // 고정하려는 건 방 사이가 아니라 <b>한 방 안에서</b> 삭제가 닫기보다 먼저라는 것이다.
        InOrder inOrder = inOrder(liveMediaManager);
        for (UUID id : ids.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList()) {
            inOrder.verify(liveMediaManager).deleteIngress(eq(id), anyString());
            inOrder.verify(liveMediaManager).closeRoom(id.toString());
        }
    }

    /**
     * 삭제 포트는 실패를 삼킨다(비-2xx·예외 모두). "요청했다"와 "지워졌다"가 다르므로, 전부 실패해도
     * 방 닫기까지 그대로 흘러간다 — 그러면 살아남은 ingress 로 OBS 가 방을 되살린다. 불변식이
     * <b>실패 경로에서만</b> 깨지는 모양이라 더 안 보인다. 닫기 직전 잔존 확인이 그걸 막는다.
     */
    @Test
    @DisplayName("ingress 삭제가 조용히 실패해 잔존이 남으면 방을 닫지 않는다")
    void silentlyFailedIngressDelete_leavesTheRoomOpen() {
        givenLiveKit(List.of(new IngressSummary("ing-1", roomId.toString(), false)),
                List.of(), List.of(new RoomSummary(roomId.toString(), 0, OLD)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.Rtmp("stored"), OLD)));
        // 삭제는 조용히 실패했다 — 포트가 삼키므로 예외도 반환값도 없고, 잔존만 남는다.
        given(liveMediaManager.listRoomIngress(roomId))
                .willReturn(List.of(new IngressSummary("ing-1", roomId.toString(), false)));

        service.reconcile();

        then(liveMediaManager).should().deleteIngress(roomId, "ing-1");
        then(liveMediaManager).should(never()).closeRoom(anyString());
    }

    @Test
    @DisplayName("잔존 확인이 실패하면(모르면) 방을 닫지 않는다 — 파괴 판단은 fail-closed")
    void survivorCheckFailure_leavesTheRoomOpen() {
        givenLiveKit(List.of(new IngressSummary("ing-1", roomId.toString(), false)),
                List.of(), List.of(new RoomSummary(roomId.toString(), 0, OLD)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.Rtmp("stored"), OLD)));
        given(liveMediaManager.listRoomIngress(roomId))
                .willThrow(new LiveMediaException("조회 실패"));

        service.reconcile();

        then(liveMediaManager).should(never()).closeRoom(anyString());
    }

    @Test
    @DisplayName("잔존이 없으면 그대로 방을 닫는다 — 확인이 정상 경로를 막지 않는다")
    void noSurvivors_closesTheRoom() {
        givenLiveKit(List.of(new IngressSummary("ing-1", roomId.toString(), false)),
                List.of(), List.of(new RoomSummary(roomId.toString(), 0, OLD)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.Rtmp("stored"), OLD)));
        given(liveMediaManager.listRoomIngress(roomId)).willReturn(List.of());

        service.reconcile();

        then(liveMediaManager).should().closeRoom(roomId.toString());
    }

    /**
     * 예산이 방 처리 도중에 끝나면 <b>그 방은 닫지 않는다</b> — egress 를 남긴 채 방을 닫으면
     * 순서 불변식이 깨진 것과 같은 결과(되살아난 방 + 남은 과금)가 된다.
     */
    @Test
    @DisplayName("예산이 방 처리 중에 끝나면 그 방은 닫지 않고 다음 회차로 넘긴다")
    void budgetExhaustedMidRoom_leavesTheRoomOpen() {
        ReconcileOrphanMediaService budgeted = new ReconcileOrphanMediaService(
                liveMediaManager, liveRoomRepository,
                new OrphanMediaReconcilePolicy(Duration.ofMinutes(15), Duration.ofMinutes(1)),
                timeProvider, liveMetrics);
        givenLiveKit(List.of(new IngressSummary("ing-1", roomId.toString(), false)),
                List.of(new EgressSummary("eg-1", roomId.toString(), true, OLD)),
                List.of(new RoomSummary(roomId.toString(), 1, OLD)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.Rtmp("stored"), OLD)));
        // 시작 → 방 직전 → ingress 직전(여기까지 예산 내) → egress 직전에 예산 초과
        given(timeProvider.now()).willReturn(NOW, NOW, NOW, NOW.plus(Duration.ofMinutes(5)));

        budgeted.reconcile();

        then(liveMediaManager).should().deleteIngress(roomId, "ing-1");
        then(liveMediaManager).should(never()).stopEgress(any(UUID.class), anyString());
        then(liveMediaManager).should(never()).closeRoom(anyString());
        org.assertj.core.api.Assertions.assertThat(liveMetrics.acted)
                .contains("ROUND_BUDGET_EXHAUSTED=1");
    }

    /**
     * ingress 루프에도 마감이 있어야 한다 — 경합 패자가 쌓인 방은 ingress 가 수십 건일 수 있고,
     * 이 루프만 무제한이면 "리스 ≥ 예산 + 방 하나의 fan-out" 보장이 이름뿐이 된다.
     */
    @Test
    @DisplayName("예산이 ingress 처리 중에 끝나면 남은 ingress 도 방도 다음 회차로 넘긴다")
    void budgetExhaustedMidIngress_leavesTheRoomOpen() {
        ReconcileOrphanMediaService budgeted = new ReconcileOrphanMediaService(
                liveMediaManager, liveRoomRepository,
                new OrphanMediaReconcilePolicy(Duration.ofMinutes(15), Duration.ofMinutes(1)),
                timeProvider, liveMetrics);
        givenLiveKit(List.of(
                        new IngressSummary("ing-1", roomId.toString(), false),
                        new IngressSummary("ing-2", roomId.toString(), false)),
                List.of(), List.of(new RoomSummary(roomId.toString(), 1, OLD)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.Rtmp("stored"), OLD)));
        // 시작 → 방 직전 → 첫 ingress 직전(예산 내) → 둘째 ingress 직전에 예산 초과
        given(timeProvider.now()).willReturn(NOW, NOW, NOW, NOW.plus(Duration.ofMinutes(5)));

        budgeted.reconcile();

        then(liveMediaManager).should(times(1)).deleteIngress(any(UUID.class), anyString());
        then(liveMediaManager).should(never()).closeRoom(anyString());
        org.assertj.core.api.Assertions.assertThat(liveMetrics.acted).contains("ROUND_BUDGET_EXHAUSTED=1");
    }

    /**
     * egress 는 자기 startedAt 나이로 판정한다 — 방의 updated_at 유예를 걸면 Scheduled/Ready 에서
     * updated_at 이 계속 갱신되는 방의 고아 egress 가 영영 회수되지 않고 과금만 이어진다.
     */
    @Test
    @DisplayName("egress 회수는 방의 updated_at 유예에 막히지 않는다 — 자기 나이로 판정한다")
    void egress_isJudgedByItsOwnAge_notTheRoomsGrace() {
        givenLiveKit(List.of(),
                List.of(new EgressSummary("eg-1", roomId.toString(), true, OLD)),
                List.of());
        // 방금 갱신된 Ready 방 — ingress 라면 유예에 걸려 손대지 않을 상태다.
        given(liveRoomRepository.findAllByIds(Set.of(roomId))).willReturn(List.of(
                room(new LiveStatus.Ready(NOW), new LiveStreamType.WebRtc(), NOW)));
        given(liveRoomRepository.findById(roomId)).willReturn(java.util.Optional.of(
                room(new LiveStatus.Ready(NOW), new LiveStreamType.WebRtc(), NOW)));

        service.reconcile();

        then(liveMediaManager).should().stopEgress(roomId, "eg-1");
    }

    private List<UUID> givenElevenEndedRoomsWithEveryResourceKind() {
        List<UUID> ids = java.util.stream.IntStream.range(0, 11)
                .mapToObj(ignored -> UUID.randomUUID())
                .toList();
        givenLiveKit(
                ids.stream().map(id -> new IngressSummary("ing-" + id, id.toString(), false)).toList(),
                ids.stream().map(id -> new EgressSummary("eg-" + id, id.toString(), true, OLD)).toList(),
                ids.stream().map(id -> new RoomSummary(id.toString(), 1, OLD)).toList());
        given(liveRoomRepository.findAllByIds(any())).willAnswer(invocation -> {
            Set<UUID> requested = invocation.getArgument(0);
            return requested.stream()
                    .map(id -> LiveRoom.builder()
                            .id(id)
                            .sellerId(UUID.randomUUID())
                            .title("제목")
                            .sellerNickname("닉네임")
                            .status(ended())
                            .streamType(new LiveStreamType.Rtmp("stored-" + id))
                            .updatedAt(OLD)
                            .build())
                    .toList();
        });
        return ids;
    }

    private LiveRoom room(LiveStatus status, LiveStreamType streamType, Instant updatedAt) {
        return room(roomId, status, streamType, updatedAt);
    }

    private LiveRoom room(UUID id, LiveStatus status, LiveStreamType streamType, Instant updatedAt) {
        return LiveRoom.builder()
                .id(id)
                .sellerId(UUID.randomUUID())
                .title("제목")
                .sellerNickname("닉네임")
                .status(status)
                .streamType(streamType)
                .updatedAt(updatedAt)
                .build();
    }

    private LiveStatus.Ended ended() {
        return new LiveStatus.Ended(Instant.parse("2026-06-10T09:00:00Z"), OLD, null);
    }

    private LiveStatus.Live live() {
        return new LiveStatus.Live(Instant.parse("2026-06-10T09:00:00Z"), "sfu-1", "eg-1", "https://hls/1");
    }

    /** 목록 스텁 — 세 조회는 매 회차 반드시 불린다. */
    private void givenLiveKit(List<IngressSummary> ingresses, List<EgressSummary> egresses) {
        givenLiveKit(ingresses, egresses, List.of());
    }

    private void givenLiveKit(
            List<IngressSummary> ingresses, List<EgressSummary> egresses, List<RoomSummary> sfuRooms) {
        given(timeProvider.now()).willReturn(NOW);
        given(liveMediaManager.listAllIngress()).willReturn(ingresses);
        given(liveMediaManager.listAllEgress()).willReturn(egresses);
        given(liveMediaManager.listAllRooms()).willReturn(sfuRooms);
    }

    private RoomSummary sfuRoom(int participants, Instant createdAt) {
        return new RoomSummary(roomId.toString(), participants, createdAt);
    }

    // ---------- SFU 방 ----------

    @Test
    @DisplayName("방 — 종료된 방이 LiveKit 에 살아 있으면 닫는다 (판매자 토큰 재입장으로 되살아난 방)")
    void room_endedRoomAliveInLiveKit_isClosed() {
        givenLiveKit(List.of(), List.of(), List.of(sfuRoom(1, OLD)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.WebRtc(), OLD)));

        service.reconcile();

        then(liveMediaManager).should(times(1)).closeRoom(roomId.toString());
    }

    @Test
    @DisplayName("방 — 참가자가 있어도 닫는다: 참가자 0 만 지우면 이 잡의 표적(되돌아온 판매자)을 놓친다")
    void room_closedEvenWithParticipants() {
        givenLiveKit(List.of(), List.of(), List.of(sfuRoom(3, OLD)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.WebRtc(), OLD)));

        service.reconcile();

        then(liveMediaManager).should(times(1)).closeRoom(roomId.toString());
    }

    @Test
    @DisplayName("방 — 종료되지 않은 방은 닫지 않는다: 닫으면 진행 중인 방송이 끊긴다")
    void room_openRoom_isNeverClosed() {
        givenLiveKit(List.of(), List.of(), List.of(sfuRoom(2, OLD)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(live(), new LiveStreamType.WebRtc(), OLD)));

        service.reconcile();

        then(liveMediaManager).should(never()).closeRoom(anyString());
    }

    @Test
    @DisplayName("방 — 예약 상태에서 createRoom 만 끝난 방은 닫지 않는다: 시작하려는 방송을 끊는다")
    void room_scheduledRoom_isNeverClosed() {
        givenLiveKit(List.of(), List.of(), List.of(sfuRoom(0, OLD)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(new LiveStatus.Scheduled(NOW), new LiveStreamType.WebRtc(), OLD)));

        service.reconcile();

        then(liveMediaManager).should(never()).closeRoom(anyString());
    }

    @Test
    @DisplayName("방 — 방금 만들어졌어도 닫는다: 유예를 두면 재입장이 시각을 리셋해 영영 회수되지 않는다")
    void room_recentlyCreated_isStillClosed() {
        givenLiveKit(List.of(), List.of(), List.of(sfuRoom(1, RECENT)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.WebRtc(), OLD)));

        service.reconcile();

        then(liveMediaManager).should(times(1)).closeRoom(roomId.toString());
    }

    @Test
    @DisplayName("방 — 생성 시각을 몰라도 닫는다: createdAt 은 판정이 아니라 로그용이다")
    void room_unknownCreatedAt_isStillClosed() {
        givenLiveKit(List.of(), List.of(), List.of(sfuRoom(0, null)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.WebRtc(), OLD)));

        service.reconcile();

        then(liveMediaManager).should(times(1)).closeRoom(roomId.toString());
    }

    @Test
    @DisplayName("방 — DB 에 없는 방은 닫지 않는다 (예약 저장 전이거나 남의 리소스)")
    void room_unknownToDb_isNeverClosed() {
        givenLiveKit(List.of(), List.of(), List.of(sfuRoom(0, OLD)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId))).willReturn(List.of());

        service.reconcile();

        then(liveMediaManager).should(never()).closeRoom(anyString());
    }

    @Test
    @DisplayName("방 — 우리 이름 규칙이 아니면 DB 조회도 하지 않고 건너뛴다")
    void room_nonUuidName_isIgnored() {
        givenLiveKit(List.of(), List.of(), List.of(new RoomSummary("not-a-uuid", 5, OLD)));

        service.reconcile();

        then(liveMediaManager).should(never()).closeRoom(anyString());
        then(liveRoomRepository).should(never()).findAllByIds(any());
    }

    @Test
    @DisplayName("방 — 대문자 표기는 우리 방이 아니다: UUID 파싱은 통과해도 남의 방을 지우게 된다")
    void room_nonCanonicalName_isIgnored() {
        // UUID.fromString 은 대문자·축약형도 받아 다른 문자열로 정규화한다. 걸러내지 않으면 남의 방 이름이
        // 우리 roomId 의 변형일 때 DB 는 우리 Ended 방으로 매칭되고 삭제는 그 이름으로 나간다.
        String upperCased = roomId.toString().toUpperCase();
        givenLiveKit(List.of(), List.of(), List.of(new RoomSummary(upperCased, 2, OLD)));

        service.reconcile();

        then(liveMediaManager).should(never()).closeRoom(anyString());
        then(liveRoomRepository).should(never()).findAllByIds(any());
    }

    @Test
    @DisplayName("방 — 닫을 때는 LiveKit 이 준 원문이 아니라 DB 유래 값을 넘긴다")
    void room_closedWithDbDerivedName() {
        givenLiveKit(List.of(), List.of(), List.of(sfuRoom(1, OLD)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.WebRtc(), OLD)));

        service.reconcile();

        then(liveMediaManager).should(times(1)).closeRoom(roomId.toString());
    }

    @Test
    @DisplayName("방 — 조회 실패는 회차를 중단시킨다: 빈 목록으로 삼키면 정리할 방이 없다고 읽힌다")
    void room_lookupFailure_abortsRound() {
        given(timeProvider.now()).willReturn(NOW);
        given(liveMediaManager.listAllIngress()).willReturn(List.of());
        given(liveMediaManager.listAllEgress()).willReturn(List.of());
        given(liveMediaManager.listAllRooms()).willThrow(new LiveMediaException("조회 실패"));

        assertThatThrownBy(() -> service.reconcile()).isInstanceOf(LiveMediaException.class);

        then(liveMediaManager).should(never()).closeRoom(anyString());
    }

    @Test
    @DisplayName("방 조회가 실패해도 ingress·egress 회수는 끝나 있다 — 그쪽이 과금이 이어지는 방향이다")
    void room_lookupFailure_doesNotBlockMediaCleanup() {
        given(timeProvider.now()).willReturn(NOW);
        given(liveMediaManager.listAllIngress())
                .willReturn(List.of(new IngressSummary("ing-1", roomId.toString(), false)));
        given(liveMediaManager.listAllEgress())
                .willReturn(List.of(new EgressSummary("eg-1", roomId.toString(), true, OLD)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.Rtmp("ing-1"), OLD)));
        given(liveMediaManager.listAllRooms()).willThrow(new LiveMediaException("조회 실패"));

        assertThatThrownBy(() -> service.reconcile()).isInstanceOf(LiveMediaException.class);

        // 세 조회를 한꺼번에 받고 시작하면 여기가 통째로 건너뛰어진다
        then(liveMediaManager).should(times(1)).deleteIngress(roomId, "ing-1");
        then(liveMediaManager).should(times(1)).stopEgress(roomId, "eg-1");
    }



    // ---------- ingress ----------

    @Test
    @DisplayName("ingress — 종료된 방의 ingress 는 삭제한다 (종료 정리가 실패해 남은 잔여물)")
    void ingress_endedRoom_isDeleted() {
        givenLiveKit(List.of(new IngressSummary("ing-1", roomId.toString(), false)), List.of());
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.Rtmp("ing-1"), OLD)));

        service.reconcile();

        then(liveMediaManager).should(times(1)).deleteIngress(roomId, "ing-1");
    }

    @Test
    @DisplayName("ingress — 진행 중인 방의 정본 ingress 는 건드리지 않는다")
    void ingress_currentIngressOfOpenRoom_isKept() {
        givenLiveKit(List.of(new IngressSummary("ing-1", roomId.toString(), false)), List.of());
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(new LiveStatus.Ready(NOW), new LiveStreamType.Rtmp("ing-1"), OLD)));
        service.reconcile();

        then(liveMediaManager).should(never()).deleteIngress(any(UUID.class), anyString());
    }

    @Test
    @DisplayName("ingress — DB 가 아는 id 와 다르면 삭제한다 (중복 prepare 로 생긴 고아)")
    void ingress_mismatchedId_isDeleted() {
        givenLiveKit(List.of(new IngressSummary("ing-DUP", roomId.toString(), false)), List.of());
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(new LiveStatus.Ready(NOW), new LiveStreamType.Rtmp("ing-1"), OLD)));
        given(liveRoomRepository.findById(roomId))
                .willReturn(java.util.Optional.of(
                        room(new LiveStatus.Ready(NOW), new LiveStreamType.Rtmp("ing-1"), OLD)));

        service.reconcile();

        then(liveMediaManager).should(times(1)).deleteIngress(roomId, "ing-DUP");
    }

    @Test
    @DisplayName("ingress — 방이 인정하는 ingress 는 송출 중이면 지우지 않는다")
    void ingress_publishingAndAcknowledged_isNeverDeleted() {
        givenLiveKit(List.of(new IngressSummary("ing-1", roomId.toString(), true)), List.of());
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(new LiveStatus.Ready(NOW), new LiveStreamType.Rtmp("ing-1"), OLD)));

        service.reconcile();

        then(liveMediaManager).should(never()).deleteIngress(any(UUID.class), anyString());
    }

    @Test
    @DisplayName("ingress — 방이 인정하지 않는 ingress 는 송출 중이어도 지운다: 안 지우면 Ready 방이 영구 고착한다")
    void ingress_publishingButNotAcknowledged_isDeleted() {
        // 만료 배치는 이런 방을 승격도 만료도 하지 않는다(방이 인정 안 한 ingress 라 승격 불가, 송출
        // 중이라 만료 불가). 여기서도 publishing 이라고 건너뛰면 방이 Ended 가 될 경로가 없어 교착이다.
        givenLiveKit(List.of(new IngressSummary("ing-LOSER", roomId.toString(), true)), List.of());
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(new LiveStatus.Ready(NOW), new LiveStreamType.Rtmp("ing-WINNER"), OLD)));
        given(liveRoomRepository.findById(roomId))
                .willReturn(java.util.Optional.of(
                        room(new LiveStatus.Ready(NOW), new LiveStreamType.Rtmp("ing-WINNER"), OLD)));

        service.reconcile();

        then(liveMediaManager).should(times(1)).deleteIngress(roomId, "ing-LOSER");
    }

    @Test
    @DisplayName("ingress — 종료된 방의 송출은 잔재다: 종료 정리가 실패했을 때 회수할 주체가 여기뿐이다")
    void ingress_publishingOnEndedRoom_isDeleted() {
        // 종료 시 deleteIngress 가 실패하면 판매자는 이미 받은 streamKey 로 계속 송출할 수 있다.
        // publishing 이라고 여기서도 건너뛰면 egress 과금이 이어지고 좀비 방이 남는다.
        givenLiveKit(List.of(new IngressSummary("ing-1", roomId.toString(), true)), List.of());
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.Rtmp("ing-1"), OLD)));

        service.reconcile();

        then(liveMediaManager).should(times(1)).deleteIngress(roomId, "ing-1");
    }

    @Test
    @DisplayName("ingress — 유예가 지나지 않았으면 지우지 않는다 (createIngress 직후 save 전인 정상 요청)")
    void ingress_withinGrace_isKept() {
        // 이 시나리오의 방은 아직 Scheduled + WebRtc 다 — createIngress 는 끝났고 조건부 UPDATE 가 안 된 상태.
        // (Ended 로 두면 유예 면제에 걸려 이 테스트가 무엇을 재는지 알 수 없게 된다.)
        givenLiveKit(List.of(new IngressSummary("ing-DUP", roomId.toString(), false)), List.of());
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(new LiveStatus.Scheduled(NOW), new LiveStreamType.WebRtc(), RECENT)));

        service.reconcile();

        then(liveMediaManager).should(never()).deleteIngress(any(UUID.class), anyString());
    }

    @Test
    @DisplayName("ingress — 종료된 방은 유예를 기다리지 않는다: 종료 직후 크래시면 판매자가 계속 push 할 수 있다")
    void ingress_endedRoom_skipsGrace() {
        // 종료 트랜잭션이 updated_at 을 갱신하므로, 유예를 적용하면 종료 직후 15분간 무조건 건너뛴다.
        givenLiveKit(List.of(new IngressSummary("ing-1", roomId.toString(), true)), List.of());
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.Rtmp("ing-1"), RECENT)));

        service.reconcile();

        then(liveMediaManager).should(times(1)).deleteIngress(roomId, "ing-1");
    }

    @Test
    @DisplayName("ingress — DB 에 방이 없으면 로그만 남기고 지우지 않는다")
    void ingress_roomNotInDb_isKept() {
        givenLiveKit(List.of(new IngressSummary("ing-1", roomId.toString(), false)), List.of());
        given(liveRoomRepository.findAllByIds(Set.of(roomId))).willReturn(List.of());

        service.reconcile();

        then(liveMediaManager).should(never()).deleteIngress(any(UUID.class), anyString());
    }

    @Test
    @DisplayName("roomName 이 roomId 형식이 아니면 대상에서 제외한다 (우리 방이 아님)")
    void nonUuidRoomName_isSkipped() {
        givenLiveKit(List.of(new IngressSummary("ing-1", "not-a-uuid", false)),
                List.of(new EgressSummary("eg-1", "not-a-uuid", true, OLD)));

        service.reconcile();

        then(liveMediaManager).should(never()).deleteIngress(any(UUID.class), anyString());
        then(liveMediaManager).should(never()).stopEgress(any(UUID.class), anyString());
    }

    // ---------- egress ----------

    @Test
    @DisplayName("egress — Live 가 아닌 방의 활성 egress 는 중단한다 (DB 는 끝났는데 인코딩 과금이 계속된다)")
    void egress_nonLiveRoom_isStopped() {
        givenLiveKit(List.of(), List.of(new EgressSummary("eg-1", roomId.toString(), true, OLD)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.WebRtc(), OLD)));

        service.reconcile();

        then(liveMediaManager).should(times(1)).stopEgress(roomId, "eg-1");
    }

    @Test
    @DisplayName("egress — 스냅샷 뒤 Live로 시작된 방은 중단 직전 재확인해 보존한다")
    void egress_roomStartedAfterSnapshot_isKept() {
        givenLiveKit(List.of(), List.of(new EgressSummary("eg-old", roomId.toString(), true, OLD)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(new LiveStatus.Ready(OLD), new LiveStreamType.WebRtc(), OLD)));
        given(liveRoomRepository.findById(roomId))
                .willReturn(java.util.Optional.of(room(live(), new LiveStreamType.WebRtc(), NOW)));

        service.reconcile();

        then(liveMediaManager).should(never()).stopEgress(any(UUID.class), anyString());
    }

    @Test
    @DisplayName("egress — Live 인 방의 egress 는 정상이므로 중단하지 않는다")
    void egress_liveRoom_isKept() {
        givenLiveKit(List.of(), List.of(new EgressSummary("eg-1", roomId.toString(), true, OLD)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(live(), new LiveStreamType.WebRtc(), OLD)));

        service.reconcile();

        then(liveMediaManager).should(never()).stopEgress(any(UUID.class), anyString());
    }

    @Test
    @DisplayName("egress — startedAt 이 없으면 나이를 알 수 없어 건드리지 않는다")
    void egress_withoutStartedAt_isKept() {
        givenLiveKit(List.of(), List.of(new EgressSummary("eg-1", roomId.toString(), true, null)));

        service.reconcile();

        then(liveMediaManager).should(never()).stopEgress(any(UUID.class), anyString());
    }

    @Test
    @DisplayName("egress — 이미 멈춘 egress 는 대상이 아니다 (비용이 나가지 않는다)")
    void egress_inactive_isKept() {
        givenLiveKit(List.of(), List.of(new EgressSummary("eg-1", roomId.toString(), false, OLD)));

        service.reconcile();

        then(liveMediaManager).should(never()).stopEgress(any(UUID.class), anyString());
        // 이 방은 DB 조회 대상에서도 빠진다 — listAllEgress 는 종료 egress 이력까지 실어 오므로,
        // 걸러내지 않으면 IN 절이 이력 누적만큼 커진다.
        then(liveRoomRepository).should(never()).findAllByIds(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("egress — 한 방의 화질별 egress는 스냅샷 ID를 각각 중단한다")
    void egress_sameRoomMultipleEgresses_stopsSnapshotIds() {
        givenLiveKit(List.of(), List.of(
                new EgressSummary("eg-1080", roomId.toString(), true, OLD),
                new EgressSummary("eg-720", roomId.toString(), true, OLD),
                new EgressSummary("eg-360", roomId.toString(), true, OLD)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.WebRtc(), OLD)));

        service.reconcile();

        then(liveMediaManager).should().stopEgress(roomId, "eg-1080");
        then(liveMediaManager).should().stopEgress(roomId, "eg-720");
        then(liveMediaManager).should().stopEgress(roomId, "eg-360");
    }

    /**
     * 회귀 방지: 한때 방 안쪽 egress 를 바깥과 <b>같은 round</b> 로 회전시키며 3건에서 끊었다.
     * 두 창 수가 서로소가 아니면 특정 (방, egress) 쌍이 영원히 선택되지 않는데(방 20 × egress 6 이면
     * 절반이 미방문), 회차는 completed 로 EGRESS_STOP_REQUESTED 도 정상값으로 남아 조용했다.
     * 남긴 egress 는 계속 과금되므로 고른 방은 <b>전부</b> 끊어야 한다.
     */
    @Test
    @DisplayName("egress — 한 방에 렌디션이 3개를 넘어도 고른 방의 고아 egress 는 전부 중단한다")
    void egress_moreThanThreeRenditions_stopsAllOfThem() {
        List<EgressSummary> six = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> new EgressSummary("eg-" + i, roomId.toString(), true, OLD))
                .toList();
        givenLiveKit(List.of(), six);
        given(liveRoomRepository.findAllByIds(Set.of(roomId)))
                .willReturn(List.of(room(ended(), new LiveStreamType.WebRtc(), OLD)));

        service.reconcile();

        for (int i = 0; i < 6; i++) {
            then(liveMediaManager).should().stopEgress(roomId, "eg-" + i);
        }
    }

    @Test
    @DisplayName("회차 예산을 넘기면 남은 후보를 다음 회차로 넘긴다 — 락이 실질적으로 필요한 유일한 잡이다")
    void roundBudgetExhausted_stopsMidRound() {
        ReconcileOrphanMediaService budgeted = new ReconcileOrphanMediaService(
                liveMediaManager, liveRoomRepository,
                new OrphanMediaReconcilePolicy(Duration.ofMinutes(15), Duration.ofMinutes(1)),
                timeProvider, liveMetrics);
        UUID other = UUID.randomUUID();
        // givenLiveKit 을 쓰지 않는다 — 예산이 소진되면 방 스윕은 전수 조회부터 건너뛰므로
        // listAllRooms 스텁이 미사용으로 남는다(그 자체가 이 테스트가 고정하려는 동작이다).
        given(liveMediaManager.listAllIngress()).willReturn(List.of());
        given(liveMediaManager.listAllEgress()).willReturn(List.of(
                new EgressSummary("eg-a", roomId.toString(), true, OLD),
                new EgressSummary("eg-b", other.toString(), true, OLD)));
        given(liveRoomRepository.findAllByIds(Set.of(roomId, other)))
                .willReturn(List.of(room(ended(), new LiveStreamType.WebRtc(), OLD),
                        room(other, ended(), new LiveStreamType.WebRtc(), OLD)));
        // 시작 → 첫 방 직전 → 그 방의 첫 egress 직전(여기까지 예산 내) → 그 뒤로는 예산 초과
        given(timeProvider.now()).willReturn(NOW, NOW, NOW, NOW.plus(Duration.ofMinutes(5)));

        budgeted.reconcile();

        then(liveMediaManager).should(times(1)).stopEgress(any(UUID.class), anyString());
        org.assertj.core.api.Assertions.assertThat(liveMetrics.acted)
                .contains("ROUND_BUDGET_EXHAUSTED=1");
    }


    // ---------- 조회 실패 ----------

    @Test
    @DisplayName("LiveKit 조회가 실패하면 회차 전체를 실패시킨다 — 빈 목록을 '고아 없음'으로 오독하면 안 된다")
    void listFailure_propagates() {
        given(timeProvider.now()).willReturn(NOW);
        given(liveMediaManager.listAllIngress()).willThrow(new LiveMediaException("조회 실패"));

        assertThatThrownBy(() -> service.reconcile()).isInstanceOf(LiveMediaException.class);

        then(liveRoomRepository).should(never()).findAllByIds(any());
        then(liveMediaManager).should(never()).deleteIngress(any(UUID.class), anyString());
    }

    @Test
    @DisplayName("이 잡이 매 회차 활성 egress 게이지를 갱신한다 — 방치 Live 잡은 후보가 있을 때만 거기까지 가므로 평상시엔 갱신되지 않는다")
    void everyRound_refreshesActiveEgressGauge() {
        UUID other = UUID.randomUUID();
        givenLiveKit(List.of(), List.of(
                new EgressSummary("eg-1", UUID.randomUUID().toString(), true, OLD),
                new EgressSummary("eg-2", other.toString(), false, OLD)));

        service.reconcile();

        // active 인 것만 센다 — 멈춘 egress 는 방송이 아니다
        org.assertj.core.api.Assertions.assertThat(liveMetrics.liveKitEgressRooms).isEqualTo(1);
    }

    @Test
    @DisplayName("LiveKit 이 전 목록을 비워 줘도 회차 중단으로 세지 않는다 — 방송 없는 새벽과 구분이 안 돼 매일 울리는 알람이 된다")
    void allListsEmpty_isNotCountedAsAbortedRound() {
        givenLiveKit(List.of(), List.of());

        service.reconcile();

        org.assertj.core.api.Assertions.assertThat(liveMetrics.abortedRounds).isEmpty();
        // 대신 게이지가 0 으로 떨어져 DB 쪽 활성 방 수와의 괴리로 드러난다
        org.assertj.core.api.Assertions.assertThat(liveMetrics.liveKitEgressRooms).isZero();
    }

    @Test
    @DisplayName("예외로 죽은 회차는 이 잡의 이름으로 failed 를 센다")
    void failedRound_isCountedWithOwnJobName() {
        given(timeProvider.now()).willReturn(NOW);
        given(liveMediaManager.listAllIngress()).willThrow(new IllegalStateException("LiveKit 장애"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.reconcile())
                .isInstanceOf(IllegalStateException.class);

        org.assertj.core.api.Assertions.assertThat(liveMetrics.failedRounds)
                .containsExactly(ReconcileJob.ORPHAN_MEDIA);
        org.assertj.core.api.Assertions.assertThat(liveMetrics.completedRounds).isEmpty();
    }
}
