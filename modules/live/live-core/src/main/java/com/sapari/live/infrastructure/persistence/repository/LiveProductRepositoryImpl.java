package com.sapari.live.infrastructure.persistence.repository;

import lombok.RequiredArgsConstructor;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.sapari.live.domain.model.LiveProduct;
import com.sapari.live.domain.repository.LiveProductRepository;
import com.sapari.live.infrastructure.persistence.entity.LiveProductEntity;

@Repository
@RequiredArgsConstructor
public class LiveProductRepositoryImpl implements LiveProductRepository {

    private final LiveProductJpaRepository liveProductJpaRepository;

    @Override
    public void saveAll(List<LiveProduct> products) {
        List<LiveProductEntity> entities = products.stream()
                .map(p -> LiveProductEntity.builder()
                        .liveRoomId(p.liveRoomId())
                        .productId(p.productId())
                        .originalPrice(p.originalPrice())
                        .discountPrice(p.discountPrice())
                        .liveDiscountPrice(p.liveDiscountPrice())
                        .isPinned(p.isPinned())
                        .sortOrder(p.sortOrder())
                        .pinnedAt(p.pinnedAt())
                        .build())
                .toList();
        liveProductJpaRepository.saveAll(entities);
    }
}
