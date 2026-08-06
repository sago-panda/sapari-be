package com.sapari.live.infrastructure.persistence.repository;

import java.time.Instant;
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

public interface LiveRoomJpaRepository extends JpaRepository<LiveRoomEntity, UUID> {
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
               SET r.ingressId = :ingressId, r.streamType = :rtmp, r.updatedAt = :now
             WHERE r.id = :roomId AND r.sellerId = :sellerId
               AND r.liveStatus = :scheduled AND r.ingressId IS NULL
            """)
    int assignRtmpIngressIfAbsent(@Param("roomId") UUID roomId,
                                  @Param("sellerId") UUID sellerId,
                                  @Param("ingressId") String ingressId,
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
}
