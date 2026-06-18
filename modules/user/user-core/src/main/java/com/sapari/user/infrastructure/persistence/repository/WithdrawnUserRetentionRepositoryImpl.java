package com.sapari.user.infrastructure.persistence.repository;

import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.sapari.user.domain.model.WithdrawnUserRetention;
import com.sapari.user.domain.repository.WithdrawnUserRetentionRepository;
import com.sapari.user.infrastructure.persistence.mapper.WithdrawnUserRetentionMapper;

@Repository
@RequiredArgsConstructor
public class WithdrawnUserRetentionRepositoryImpl implements WithdrawnUserRetentionRepository {

    private final WithdrawnUserRetentionJpaRepository withdrawnUserRetentionJpaRepository;
    private final WithdrawnUserRetentionMapper withdrawnUserRetentionMapper;

    @Override
    public WithdrawnUserRetention save(WithdrawnUserRetention withdrawnUserRetention) {
        return withdrawnUserRetentionMapper.toDomain(
                withdrawnUserRetentionJpaRepository.save(withdrawnUserRetentionMapper.toEntity(withdrawnUserRetention))
        );
    }

    @Override
    public boolean existsByOriginalUserId(UUID originalUserId) {
        return withdrawnUserRetentionJpaRepository.existsByOriginalUserId(originalUserId);
    }

    @Override
    public int deleteExpiredBefore(Instant now) {
        return withdrawnUserRetentionJpaRepository.deleteByRetentionUntilLessThanEqual(now);
    }

    @Override
    public int deleteByOriginalUserId(UUID originalUserId) {
        return withdrawnUserRetentionJpaRepository.deleteByOriginalUserId(originalUserId);
    }
}
