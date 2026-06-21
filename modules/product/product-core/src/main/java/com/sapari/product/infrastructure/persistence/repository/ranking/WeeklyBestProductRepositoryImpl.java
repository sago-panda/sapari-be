package com.sapari.product.infrastructure.persistence.repository.ranking;

import com.sapari.product.domain.model.ranking.WeeklyBestProduct;
import com.sapari.product.domain.repository.ranking.WeeklyBestProductRepository;
import com.sapari.product.infrastructure.persistence.mapper.ranking.WeeklyBestProductMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 주간 베스트 랭킹 영속 어댑터. 배치가 주차별로 생성하는 append-only(save=INSERT 전용).
 */
@Repository
@RequiredArgsConstructor
public class WeeklyBestProductRepositoryImpl implements WeeklyBestProductRepository {

    private final WeeklyBestProductJpaRepository jpaRepository;
    private final WeeklyBestProductMapper mapper;

    @Override
    public WeeklyBestProduct save(WeeklyBestProduct best) {
        // 주간 베스트는 배치 생성(append-only).
        return mapper.toDomain(jpaRepository.save(mapper.toEntity(best)));
    }

    @Override
    public Optional<WeeklyBestProduct> findById(UUID id) {
        return jpaRepository.findById(id)
                .map(mapper::toDomain);
    }

    @Override
    public List<WeeklyBestProduct> findByWeekStart(LocalDate weekStart) {
        return jpaRepository.findByWeekStartOrderByRank(weekStart)
                .stream()
                .map(mapper::toDomain)
                .toList();
    }
}
