package com.sapari.live.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sapari.live.infrastructure.persistence.entity.LiveRoomEntity;
import com.sapari.live.infrastructure.persistence.entity.LiveRoomStatus;
import com.sapari.live.infrastructure.persistence.entity.StreamType;
import com.sapari.live.infrastructure.persistence.entity.VodStatus;

public interface LiveRoomJpaRepository extends JpaRepository<LiveRoomEntity, UUID> {

    /*
     * 잠금 대기 상한(lock_timeout)은 아직 없다. 시작 계열이 미디어 I/O 를 트랜잭션 안에서 부르므로
     * (AGENTS "Media ports" 참고) 같은 방을 노리는 다른 전이가 그만큼 기다린다.
     *
     * 여기에 JPA 힌트를 붙이는 것으로는 안 된다 — PostgreSQL 은 NOWAIT / SKIP LOCKED 만 받고
     * jakarta.persistence.lock.timeout 의 숫자 대기값은 <b>조용히 무시</b>된다(붙여도 아무 일이 없는데
     * 붙였다는 사실만 남는다). 세션 파라미터를 HikariConfig 로 거는 게 유일한 경로이고,
     * application*.yml 이 미추적이라 코드 쪽 빈이어야 한다.
     */
    Optional<LiveRoomEntity> findByIdAndSellerId(UUID id, UUID sellerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LiveRoomEntity> findWithLockById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LiveRoomEntity> findWithLockByIdAndSellerId(UUID id, UUID sellerId);

    /**
     * 아직 ingress 가 없는 Scheduled 방에만 RTMP ingress 를 배정한다.
     *
     * <p>{@code prepare} 는 {@code createIngress}(외부 I/O)를 트랜잭션 밖에서 부르려고 행 잠금을 쓰지 않는다.
     * 그래서 "조회 → 가드 → 발급 → 저장"이 열려 있고 동시 요청이 각각 ingress 를 만든다. 검사와 쓰기를 이
     * UPDATE 한 문장으로 합쳐 DB 가 배타성을 보장하게 한다 — <b>이 경로에서는 WHERE 절이 도메인 가드를
     * 대신한다</b>({@code canPrepareIngress}/{@code isRtmp}). 상태를 추가할 때 이 조건도 함께 봐야 한다.
     *
     * <p>{@code updated_at} 을 명시하는 건 벌크 UPDATE 가 영속성 컨텍스트를 우회해 {@code @LastModifiedDate}
     * 가 붙지 않기 때문이다. 그 값은 Ready 만료 배치의 기준이라 빠지면 조용히 어긋난다.
     *
     * @return 갱신된 행 수(1 이면 획득, 0 이면 경합에서 짐)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE LiveRoomEntity r
               SET r.ingressId = :ingressId, r.rtmpUrl = :rtmpUrl, r.streamType = :rtmp, r.updatedAt = :now
             WHERE r.id = :roomId AND r.sellerId = :sellerId
               AND r.liveStatus = :scheduled AND r.ingressId IS NULL
            """)
    int assignRtmpIngressIfAbsent(@Param("roomId") UUID roomId,
                                  @Param("sellerId") UUID sellerId,
                                  @Param("ingressId") String ingressId,
                                  @Param("rtmpUrl") String rtmpUrl,
                                  @Param("rtmp") StreamType rtmp,
                                  @Param("scheduled") LiveRoomStatus scheduled,
                                  @Param("now") Instant now);

    /** Ready 고착 판정용. arm 시각이 곧 updated_at 이라 "시작 버튼 이후 경과"를 잰다. */
    List<LiveRoomEntity> findByLiveStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            LiveRoomStatus liveStatus, Instant threshold, Limit limit);

    /**
     * Live 고착 판정용. updated_at 을 재사용하면 안 된다 — 방송 중 제목 수정 같은 저장에도 갱신돼
     * 진짜 방치된 방이 후보에서 계속 빠진다. started_at 은 applyLive 이후 변하지 않는다.
     */
    List<LiveRoomEntity> findByLiveStatusAndStartedAtBeforeOrderByStartedAtAsc(
            LiveRoomStatus liveStatus, Instant threshold, Limit limit);

    /** 활성 방 수 게이지용. 인덱스가 있는 상태 컬럼 하나짜리 집계다. */
    long countByLiveStatus(LiveRoomStatus liveStatus);

    /*
     * 판매자 내 방 목록 키셋 — 정렬과 WHERE 가 같은 두 키(scheduled_at DESC, id DESC)를 쓴다.
     * 인덱스 (seller_id, scheduled_at DESC, id DESC) 가 짝이다(V202611081200). 첫 페이지와 이후 페이지를
     * 두 쿼리로 나눴다 — "커서가 null 이면 조건 무시" 같은 OR 분기 없이 키셋 조건이 그대로 남게.
     * scheduled_at 은 create() 가 필수로 받고 어떤 상태도 지우지 않아 NULL 행이 없다는 전제다.
     */
    @Query("""
            SELECT r FROM LiveRoomEntity r
             WHERE r.sellerId = :sellerId AND r.liveStatus IN :statuses
             ORDER BY r.scheduledAt DESC, r.id DESC
            """)
    List<LiveRoomEntity> findSellerRoomsFirstPage(@Param("sellerId") UUID sellerId,
                                                  @Param("statuses") Collection<LiveRoomStatus> statuses,
                                                  Limit limit);

    @Query("""
            SELECT r FROM LiveRoomEntity r
             WHERE r.sellerId = :sellerId AND r.liveStatus IN :statuses
               AND (r.scheduledAt < :scheduledAt OR (r.scheduledAt = :scheduledAt AND r.id < :id))
             ORDER BY r.scheduledAt DESC, r.id DESC
            """)
    List<LiveRoomEntity> findSellerRoomsAfter(@Param("sellerId") UUID sellerId,
                                              @Param("statuses") Collection<LiveRoomStatus> statuses,
                                              @Param("scheduledAt") Instant scheduledAt,
                                              @Param("id") UUID id,
                                              Limit limit);

    /** 탭별 개수용. 기존 인덱스 (seller_id, status, created_at) 의 앞 두 키로 풀린다. */
    @Query("SELECT r.liveStatus, COUNT(r) FROM LiveRoomEntity r WHERE r.sellerId = :sellerId GROUP BY r.liveStatus")
    List<Object[]> countBySellerGroupByStatus(@Param("sellerId") UUID sellerId);

    /** 공개 방송 중 목록 재조정의 원본 — 지금 Live 인 방 전부(상한 limit). */
    List<LiveRoomEntity> findByLiveStatusOrderByIdAsc(LiveRoomStatus liveStatus, Limit limit);

    /** 공개 예정 목록 첫 페이지. 정렬은 {@code scheduledAt, id} — 커서가 그 둘을 싣는다. */
    @Query("""
            SELECT r FROM LiveRoomEntity r
             WHERE r.liveStatus IN :statuses
             ORDER BY r.scheduledAt ASC, r.id ASC
            """)
    List<LiveRoomEntity> findUpcoming(@Param("statuses") Collection<LiveRoomStatus> statuses, Limit limit);

    /** 공개 예정 목록 다음 페이지 — {@code (scheduledAt, id)} 가 커서보다 뒤인 행(keyset). */
    @Query("""
            SELECT r FROM LiveRoomEntity r
             WHERE r.liveStatus IN :statuses
               AND (r.scheduledAt > :afterScheduledAt
                    OR (r.scheduledAt = :afterScheduledAt AND r.id > :afterId))
             ORDER BY r.scheduledAt ASC, r.id ASC
            """)
    List<LiveRoomEntity> findUpcomingAfter(@Param("statuses") Collection<LiveRoomStatus> statuses,
                                           @Param("afterScheduledAt") Instant afterScheduledAt,
                                           @Param("afterId") UUID afterId,
                                           Limit limit);

    /**
     * 다시보기 처리 상태를 앞으로만 옮긴다 — {@code vod_status IN (:from)} 이 단조성 가드다(중복·역순 알림은 0행).
     * 네이티브인 이유: 엔티티는 이 두 컬럼을 {@code insertable/updatable = false} 로 매핑해 저장 경로가 쓰지 못하게
     * 막았다(전체 컬럼 UPDATE 가 웹훅 결과를 낡은 값으로 되돌린다). {@code updated_at} 은 일부러 쓰지 않는다 —
     * 고아 미디어 정리 잡이 그 값을 "방을 마지막으로 손댄 시각"으로 유예 판정에 쓴다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE live_schema.live_rooms
               SET vod_status = :target, vod_failed_reason = :reason
             WHERE egress_id = :egressId AND vod_status IN (:from)
            """, nativeQuery = true)
    int advanceVodStatus(@Param("egressId") String egressId,
                         @Param("target") String target,
                         @Param("reason") String reason,
                         @Param("from") Collection<String> from);

    /*
     * 공개 다시보기 목록 키셋 — 정렬과 WHERE 가 같은 두 키(ended_at DESC, id DESC).
     * 인덱스 (status, vod_status, ended_at DESC, id DESC) 가 짝이다(V202611121200). 아카이브 조건은
     * GetLiveReplayService·LiveRoomMapper 의 "다시보기 없음" 판정(빈 값, 레거시 hls_archive_url = hls_url)과 같다.
     * ENDED 는 endLive/expire 가 ended_at 을 반드시 채운다는 전제다.
     */
    @Query("""
            SELECT r FROM LiveRoomEntity r
             WHERE r.liveStatus = :ended AND r.vodStatus = :ready AND r.isVodPublic = true
               AND r.hlsArchiveUrl IS NOT NULL AND TRIM(r.hlsArchiveUrl) <> ''
               AND (r.hlsUrl IS NULL OR r.hlsArchiveUrl <> r.hlsUrl)
             ORDER BY r.endedAt DESC, r.id DESC
            """)
    List<LiveRoomEntity> findReplays(@Param("ended") LiveRoomStatus ended,
                                     @Param("ready") VodStatus ready,
                                     Limit limit);

    @Query("""
            SELECT r FROM LiveRoomEntity r
             WHERE r.liveStatus = :ended AND r.vodStatus = :ready AND r.isVodPublic = true
               AND r.hlsArchiveUrl IS NOT NULL AND TRIM(r.hlsArchiveUrl) <> ''
               AND (r.hlsUrl IS NULL OR r.hlsArchiveUrl <> r.hlsUrl)
               AND (r.endedAt < :endedAt OR (r.endedAt = :endedAt AND r.id < :id))
             ORDER BY r.endedAt DESC, r.id DESC
            """)
    List<LiveRoomEntity> findReplaysAfter(@Param("ended") LiveRoomStatus ended,
                                          @Param("ready") VodStatus ready,
                                          @Param("endedAt") Instant endedAt,
                                          @Param("id") UUID id,
                                          Limit limit);

    /*
     * 놓친 녹화 종료 알림의 대체 — 조건은 백필 V202611121201 과 같다(종료 ∧ 아카이브 있음 ∧ 레거시 복사 아님).
     * advanceVodStatus 처럼 NONE/PROCESSING 에서만 옮기므로 끝 상태(READY/FAILED)는 건드리지 않고, 그 뒤 늦게 온
     * FAILED 는 0행이 된다. updated_at 은 쓰지 않는다(advanceVodStatus 주석과 같은 이유).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE live_schema.live_rooms
               SET vod_status = 'READY'
             WHERE status = 'ENDED'
               AND vod_status IN ('NONE', 'PROCESSING')
               AND ended_at < :endedBefore
               AND hls_archive_url IS NOT NULL
               AND TRIM(hls_archive_url) <> ''
               AND (hls_url IS NULL OR hls_archive_url <> hls_url)
            """, nativeQuery = true)
    int recoverStuckReplays(@Param("endedBefore") Instant endedBefore);
}
