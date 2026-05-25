package com.sapari.seller.infrastructure.persistence.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sapari.seller.infrastructure.persistence.entity.LocalCredentialEntity;

public interface LocalCredentialJpaRepository extends JpaRepository<LocalCredentialEntity, UUID> {
}
