package com.sapari.live.infrastructure.persistence.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityNotFoundException;

import org.springframework.stereotype.Repository;

import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.infrastructure.persistence.entity.LiveRoomEntity;
import com.sapari.live.infrastructure.persistence.mapper.LiveRoomMapper;

@Slf4j
@Repository
@RequiredArgsConstructor
public class LiveRoomRepositoryImpl implements LiveRoomRepository {

    private final LiveRoomJpaRepository liveRoomJpaRepository;

    @Override
    public LiveRoom save(LiveRoom liveRoom){
        //신규 생성
        if(liveRoom.id() == null){
            LiveRoomEntity entity = LiveRoomMapper.toEntity(liveRoom);
            LiveRoomEntity saved = liveRoomJpaRepository.save(entity);

            return LiveRoomMapper.toDomain(saved);
        }
        else{
            LiveRoomEntity existingEntity = liveRoomJpaRepository.findById(liveRoom.id())
                    .orElseThrow(() -> new EntityNotFoundException("해당 라이브 방을 찾을 수 없습니다."));

            LiveRoomMapper.updateEntityFromDomain(existingEntity, liveRoom);

            return LiveRoomMapper.toDomain(liveRoomJpaRepository.save(existingEntity));
        }
    }

    @Override
    public Optional<LiveRoom> findById(UUID id){
        return liveRoomJpaRepository.findById(id)
                .map(LiveRoomMapper::toDomain);
    }

    @Override
    public Optional<LiveRoom> findByIdAndSellerId(UUID id, UUID hostId){
        return liveRoomJpaRepository.findByIdAndSellerId(id, hostId)
                .map(LiveRoomMapper::toDomain);
    }
}
