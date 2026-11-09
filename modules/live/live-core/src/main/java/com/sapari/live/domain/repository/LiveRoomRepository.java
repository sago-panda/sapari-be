package com.sapari.live.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;

public interface LiveRoomRepository {
    LiveRoom save(LiveRoom liveRoom);
    Optional<LiveRoom> findById(UUID id);
    Optional<LiveRoom> findByIdAndSellerId(UUID id, UUID hostId);
    /**
     * 상태 전이 경합(orphan liveRoom 삭제, ingress_started go-live)을 직렬화하기 위해 행 잠금으로 조회한다.
     */
    Optional<LiveRoom> findByIdForUpdate(UUID id);
    /**
     * 소유권 검사 + 행 잠금. 판매자 시작/종료가 webhook go-live·만료 배치와 같은 방을 두고 경합하므로 전이 전에 직렬화한다.
     * 서비스 비교로 쪼개면 "없는 방"과 "남의 방"이 갈려 존재 여부가 새므로 소유권을 쿼리에 남긴다.
     */
    Optional<LiveRoom> findByIdAndSellerIdForUpdate(UUID id, UUID hostId);

    /**
     * {@code threshold} 이전에 갱신된 Ready 방 id 를 오래된 순으로 최대 {@code limit} 건.
     * 오래된 순인 건 {@code limit} 때문이다 — 정렬이 없으면 특정 방이 계속 밀려 영영 만료되지 않는다.
     *
     * <p>반환은 <b>후보</b>일 뿐이다. 조회와 처리 사이에 OBS 가 붙어 Live 로 전환된 방이 섞일 수 있으므로
     * 호출자는 {@code findByIdForUpdate} 로 잠금 재조회해야 하고, 그때 {@code expire()} 가
     * {@code InvalidLiveStateException} 을 던진다(no-op 이 아니다). 배치 루프가 건별로 스킵할 것.
     */
    List<UUID> findExpiredReadyRoomIds(Instant threshold, int limit);

    /**
     * 아직 ingress 가 없는 Scheduled 방에만 RTMP ingress 를 배정한다. 검사와 쓰기가 한 문장이라
     * 동시 요청 중 하나만 성공한다 — {@code prepare} 는 외부 호출을 트랜잭션 밖에 두려고 행 잠금을
     * 쓰지 않아서, 배타성을 여기서 얻는다.
     *
     * @return 획득했으면 true. false 면 이미 다른 요청이 배정했거나 방이 Scheduled 가 아니다.
     */
    boolean assignRtmpIngressIfAbsent(UUID roomId, UUID sellerId, String ingressId, String rtmpUrl, Instant now);

    /**
     * {@code threshold} 이전에 시작된 Live 방 id 를 오래된 순으로 최대 {@code limit} 건.
     *
     * <p><b>이것만으로 종료를 판단하면 안 된다.</b> 정상적으로 오래 진행 중인 방송도 전부 걸린다 —
     * 호출자가 LiveKit 에 활성 egress 가 없음을 확인한 방만 종료해야 한다.
     */
    List<UUID> findStaleLiveRoomIds(Instant threshold, int limit);

    /**
     * 고아 미디어 대조용 벌크 조회. 읽기 전용이라 행 잠금을 걸지 않는다 — LiveKit 리소스를 지울 뿐
     * 방 상태는 건드리지 않으므로 직렬화할 전이가 없다.
     */
    List<LiveRoom> findAllByIds(Set<UUID> ids);

    /**
     * 지금 LIVE 상태인 방 수. 관측 게이지 전용이라 목록이 아니라 개수만 센다.
     *
     * <p>도메인 판단에 쓰지 말 것 — 스냅샷이라 읽는 순간 이미 과거이고, 정리 잡처럼 방을 건드리는
     * 결정은 방마다 잠금 재조회로 판정해야 한다.
     */
    long countLiveRooms();

    /**
     * 판매자 내 방 목록의 한 페이지. {@code scheduled_at DESC, id DESC} 키셋이다 — 커서 둘이 모두 null 이면
     * 첫 페이지, 아니면 그 행 다음부터. 호출자는 다음 페이지 판정을 위해 {@code size + 1} 을 {@code limit} 로 넘긴다.
     *
     * @param statuses 포함할 상태 종류(비어 있으면 안 된다). 탭 묶음은 호출자가 정한다.
     */
    List<LiveRoom> findSellerRooms(UUID sellerId, Set<Class<? extends LiveStatus>> statuses,
                                   Instant cursorScheduledAt, UUID cursorId, int limit);

    /** 판매자 방을 상태 종류별로 센다. 방이 없는 상태는 키가 없다. */
    Map<Class<? extends LiveStatus>, Long> countSellerRoomsByStatus(UUID sellerId);

    /** 지금 Live 인 방, 최대 {@code limit} 건. 공개 목록 재조정의 원본이다. */
    List<LiveRoom> findAllLive(int limit);

    /**
     * 아직 시작하지 않은 방(Scheduled·Ready)을 {@code scheduledAt, id} 오름차순으로, 커서 뒤부터 최대 {@code limit} 건.
     * 커서가 없으면({@code afterScheduledAt == null}) 처음부터. 상태는 포함 조건으로만 거른다 — 새 상태(취소 등)는
     * 자동으로 빠진다.
     */
    List<LiveRoom> findUpcoming(Instant afterScheduledAt, UUID afterId, int limit);
}
