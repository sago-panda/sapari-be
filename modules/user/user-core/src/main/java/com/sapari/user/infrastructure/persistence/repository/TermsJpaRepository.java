package com.sapari.user.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.sapari.user.infrastructure.persistence.entity.TermsEntity;
import com.sapari.user.model.TermsType;

@Repository
public interface TermsJpaRepository extends JpaRepository<TermsEntity, UUID> {

    Optional<TermsEntity> findByTypeAndActiveTrueAndEffectiveFromLessThanEqual(TermsType type, Instant effectiveAt);
}
