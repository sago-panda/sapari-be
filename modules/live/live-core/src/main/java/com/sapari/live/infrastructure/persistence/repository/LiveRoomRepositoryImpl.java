package com.sapari.live.infrastructure.persistence.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityNotFoundException;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.infrastructure.persistence.entity.LiveRoomEntity;
import com.sapari.live.infrastructure.persistence.entity.LiveRoomStatus;
import com.sapari.live.infrastructure.persistence.entity.StreamType;
import com.sapari.live.infrastructure.persistence.mapper.LiveRoomMapper;

@Slf4j
@Repository
@RequiredArgsConstructor
public class LiveRoomRepositoryImpl implements LiveRoomRepository {

    private final LiveRoomJpaRepository liveRoomJpaRepository;
    private final LiveRoomMapper liveRoomMapper;

    @Override
    public LiveRoom save(LiveRoom liveRoom){
        //신규 생성
        if(liveRoom.id() == null){
            LiveRoomEntity entity = liveRoomMapper.toEntity(liveRoom);
            LiveRoomEntity saved = liveRoomJpaRepository.save(entity);

            return liveRoomMapper.toDomain(saved);
        }
        else{
            LiveRoomEntity existingEntity = liveRoomJpaRepository.findById(liveRoom.id())
                    .orElseThrow(() -> new EntityNotFoundException("해당 라이브 방을 찾을 수 없습니다."));

            liveRoomMapper.updateEntityFromDomain(existingEntity, liveRoom);

            return liveRoomMapper.toDomain(liveRoomJpaRepository.save(existingEntity));
        }
    }

    @Override
    public Optional<LiveRoom> findById(UUID id){
        return liveRoomJpaRepository.findById(id)
                .map(liveRoomMapper::toDomain);
    }

    @Override
    public Optional<LiveRoom> findByIdAndSellerId(UUID id, UUID hostId){
        return liveRoomJpaRepository.findByIdAndSellerId(id, hostId)
                .map(liveRoomMapper::toDomain);
    }

    @Override
    public Optional<LiveRoom> findByIdForUpdate(UUID id){
        return liveRoomJpaRepository.findWithLockById(id)
                .map(liveRoomMapper::toDomain);
    }

    @Override
    public Optional<LiveRoom> findByIdAndSellerIdForUpdate(UUID id, UUID hostId){
        return liveRoomJpaRepository.findWithLockByIdAndSellerId(id, hostId)
                .map(liveRoomMapper::toDomain);
    }

    @Override
    public boolean assignRtmpIngressIfAbsent(UUID roomId, UUID sellerId, String ingressId, String rtmpUrl, Instant now){
        return liveRoomJpaRepository.assignRtmpIngressIfAbsent(
                roomId, sellerId, ingressId, rtmpUrl, StreamType.RTMP, LiveRoomStatus.SCHEDULED, now) == 1;
    }

    @Override
    public List<UUID> findStaleLiveRoomIds(Instant threshold, int limit){
        return liveRoomJpaRepository.findByLiveStatusAndStartedAtBeforeOrderByStartedAtAsc(
                        LiveRoomStatus.LIVE, threshold, Limit.of(limit))
                .stream().map(LiveRoomEntity::getId)
                .toList();
    }

    /**
     * 호출자(고아 정리)는 LiveKit 전수 목록에서 id 를 만든다 — 개수를 우리가 정하지 않으므로 장애
     * 복구 직후에는 수천 개가 한 번에 올 수 있다. 회차 예산은 <b>루프</b>를 끊을 뿐 이 쿼리 하나는
     * 못 끊으므로, 바인드 파라미터 수를 여기서 묶는다.
     */
    private static final int ID_CHUNK_SIZE = 500;

    @Override
    public List<LiveRoom> findAllByIds(Set<UUID> ids){
        List<UUID> all = List.copyOf(ids);
        List<LiveRoom> rooms = new ArrayList<>(all.size());
        for (int from = 0; from < all.size(); from += ID_CHUNK_SIZE) {
            rooms.addAll(liveRoomJpaRepository.findAllById(all.subList(from, Math.min(from + ID_CHUNK_SIZE, all.size())))
                    .stream().map(liveRoomMapper::toDomain)
                    .toList());
        }
        return rooms;
    }

    @Override
    public List<UUID> findExpiredReadyRoomIds(Instant threshold, int limit){
        return liveRoomJpaRepository.findByLiveStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                LiveRoomStatus.READY, threshold, Limit.of(limit)
                ).stream().map(LiveRoomEntity::getId)
                .toList();
    }

    @Override
    public long countLiveRooms(){
        return liveRoomJpaRepository.countByLiveStatus(LiveRoomStatus.LIVE);
    }

    /**
     * 도메인 상태 종류 ↔ 영속 enum. 포트가 영속 enum 을 노출하지 않게 여기서만 바꾼다.
     * 상태를 추가하면 여기에도 넣어야 한다 — 빠지면 그 상태의 방이 판매자 목록에서 조용히 사라진다
     * ({@code LiveRoomRepositoryImplStatusMapTest} 가 sealed permits 전부를 대조해 막는다).
     */
    static final Map<Class<? extends LiveStatus>, LiveRoomStatus> STATUS_COLUMN = Map.of(
            LiveStatus.Scheduled.class, LiveRoomStatus.SCHEDULED,
            LiveStatus.Ready.class, LiveRoomStatus.READY,
            LiveStatus.Live.class, LiveRoomStatus.LIVE,
            LiveStatus.Suspended.class, LiveRoomStatus.SUSPENDED,
            LiveStatus.Ended.class, LiveRoomStatus.ENDED,
            LiveStatus.Cancelled.class, LiveRoomStatus.CANCELLED);

    @Override
    public List<LiveRoom> findSellerRooms(UUID sellerId, Set<Class<? extends LiveStatus>> statuses,
                                          Instant cursorScheduledAt, UUID cursorId, int limit){
        List<LiveRoomStatus> columns = statuses.stream().map(STATUS_COLUMN::get).toList();
        List<LiveRoomEntity> rows = (cursorScheduledAt == null && cursorId == null)
                ? liveRoomJpaRepository.findSellerRoomsFirstPage(sellerId, columns, Limit.of(limit))
                : liveRoomJpaRepository.findSellerRoomsAfter(sellerId, columns, cursorScheduledAt, cursorId, Limit.of(limit));
        return rows.stream().map(liveRoomMapper::toDomain).toList();
    }

    @Override
    public Map<Class<? extends LiveStatus>, Long> countSellerRoomsByStatus(UUID sellerId){
        Map<LiveRoomStatus, Class<? extends LiveStatus>> byColumn = new EnumMap<>(LiveRoomStatus.class);
        STATUS_COLUMN.forEach((type, column) -> byColumn.put(column, type));
        Map<Class<? extends LiveStatus>, Long> counts = new HashMap<>();
        for (Object[] row : liveRoomJpaRepository.countBySellerGroupByStatus(sellerId)) {
            counts.put(byColumn.get((LiveRoomStatus) row[0]), (Long) row[1]);
        }
        return counts;
    }

    /** 예정 목록에 들어가는 상태 — 포함 조건이다. 제외 목록으로 바꾸면 새 상태가 공개 목록에 새어 나온다. */
    private static final List<LiveRoomStatus> UPCOMING = List.of(LiveRoomStatus.SCHEDULED, LiveRoomStatus.READY);

    @Override
    public List<LiveRoom> findAllLive(int limit){
        return liveRoomJpaRepository.findByLiveStatusOrderByIdAsc(LiveRoomStatus.LIVE, Limit.of(limit))
                .stream().map(liveRoomMapper::toDomain)
                .toList();
    }

    @Override
    public List<LiveRoom> findUpcoming(Instant afterScheduledAt, UUID afterId, int limit){
        List<LiveRoomEntity> rows = afterScheduledAt == null
                ? liveRoomJpaRepository.findUpcoming(UPCOMING, Limit.of(limit))
                : liveRoomJpaRepository.findUpcomingAfter(UPCOMING, afterScheduledAt, afterId, Limit.of(limit));
        return rows.stream().map(liveRoomMapper::toDomain).toList();
    }
}
