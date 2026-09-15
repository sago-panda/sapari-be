package com.sapari.live.infrastructure.persistence.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityNotFoundException;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

import com.sapari.live.domain.model.LiveRoom;
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
    public boolean assignRtmpIngressIfAbsent(UUID roomId, UUID sellerId, String ingressId, Instant now){
        return liveRoomJpaRepository.assignRtmpIngressIfAbsent(
                roomId, sellerId, ingressId, StreamType.RTMP, LiveRoomStatus.SCHEDULED, now) == 1;
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
}
