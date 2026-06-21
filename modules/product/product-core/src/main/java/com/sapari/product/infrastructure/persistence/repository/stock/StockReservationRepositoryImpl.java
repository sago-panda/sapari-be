package com.sapari.product.infrastructure.persistence.repository.stock;

import com.sapari.product.infrastructure.persistence.entity.stock.StockReservationEntity;

import com.sapari.product.domain.model.stock.StockReservation;
import com.sapari.product.domain.model.stock.StockReservationStatus;
import com.sapari.product.domain.repository.stock.StockReservationRepository;
import com.sapari.product.infrastructure.persistence.mapper.stock.StockReservationMapper;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * {@link StockReservationRepository} 영속 어댑터. 자식 없는 단일 애그리거트라 단순 upsert(id==null→INSERT).
 */
@Repository
@RequiredArgsConstructor
public class StockReservationRepositoryImpl implements StockReservationRepository {

    private final StockReservationJpaRepository jpaRepository;
    private final StockReservationMapper mapper;

    @Override
    public StockReservation save(StockReservation reservation) {
        StockReservationEntity entity;
        if (reservation.id() == null) {
            entity = jpaRepository.save(mapper.toEntity(reservation));
        } else {
            entity = jpaRepository.findById(reservation.id())
                    .orElseThrow(() -> new EntityNotFoundException("해당 재고 예약을 찾을 수 없습니다."));
            mapper.updateEntityFromDomain(entity, reservation);
            entity = jpaRepository.save(entity);
        }
        return mapper.toDomain(entity);
    }

    @Override
    public Optional<StockReservation> findById(UUID id) {
        return jpaRepository.findById(id)
                .map(mapper::toDomain);
    }

    @Override
    public List<StockReservation> findByCombinationIdAndStatus(UUID combinationId, StockReservationStatus status) {
        return jpaRepository.findByCombinationIdAndStatus(combinationId, status)
                .stream()
                .map(mapper::toDomain)
                .toList();
    }
}
