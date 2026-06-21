package com.sapari.product.infrastructure.persistence.repository.ranking;

import com.sapari.product.infrastructure.persistence.entity.ranking.WeeklyBestProductEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 주간 베스트 Spring Data 어댑터. 주차별 순위 정렬 조회.
 */
public interface WeeklyBestProductJpaRepository extends JpaRepository<WeeklyBestProductEntity, UUID> {
    List<WeeklyBestProductEntity> findByWeekStartOrderByRank(LocalDate weekStart);
}
