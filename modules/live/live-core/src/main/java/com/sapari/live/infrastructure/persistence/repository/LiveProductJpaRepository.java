package com.sapari.live.infrastructure.persistence.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sapari.live.infrastructure.persistence.entity.LiveProductEntity;

public interface LiveProductJpaRepository extends JpaRepository<LiveProductEntity, UUID> {
}
