package com.sapari.user.infrastructure.persistence.repository;

import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.sapari.user.domain.model.Terms;
import com.sapari.user.domain.repository.TermsRepository;
import com.sapari.user.infrastructure.persistence.mapper.TermsMapper;
import com.sapari.user.model.TermsType;

@Repository
@RequiredArgsConstructor
public class TermsRepositoryImpl implements TermsRepository {

    private final TermsJpaRepository termsJpaRepository;
    private final TermsMapper termsMapper;

    @Override
    public Optional<Terms> findActiveByTypeEffectiveAt(TermsType type, Instant effectiveAt) {
        return termsJpaRepository.findByTypeAndActiveTrueAndEffectiveFromLessThanEqual(type, effectiveAt)
                .map(termsMapper::toDomain);
    }
}
