package com.sapari.live.infrastructure.persistence.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
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
    public List<LiveRoom> findAllByIds(Set<UUID> ids){
        return liveRoomJpaRepository.findAllById(ids)
                .stream().map(liveRoomMapper::toDomain)
                .toList();
    }

    @Override
    public List<UUID> findExpiredReadyRoomIds(Instant threshold, int limit){
        return liveRoomJpaRepository.findByLiveStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                LiveRoomStatus.READY, threshold, Limit.of(limit)
                ).stream().map(LiveRoomEntity::getId)
                .toList();
    }
}
