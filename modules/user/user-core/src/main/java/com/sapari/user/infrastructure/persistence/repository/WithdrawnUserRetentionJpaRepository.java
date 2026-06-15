package com.sapari.user.infrastructure.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sapari.user.infrastructure.persistence.entity.WithdrawnUserRetentionEntity;

public interface WithdrawnUserRetentionJpaRepository extends JpaRepository<WithdrawnUserRetentionEntity, UUID> {

    boolean existsByOriginalUserId(UUID originalUserId);

    Optional<WithdrawnUserRetentionEntity> findByOriginalUserId(UUID originalUserId);
}
