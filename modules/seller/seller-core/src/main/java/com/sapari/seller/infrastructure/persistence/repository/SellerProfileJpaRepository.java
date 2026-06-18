package com.sapari.seller.infrastructure.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sapari.seller.infrastructure.persistence.entity.SellerProfileEntity;

public interface SellerProfileJpaRepository extends JpaRepository<SellerProfileEntity, UUID> {

    Optional<SellerProfileEntity> findByUserId(UUID userId);

    boolean existsByBusinessNumber(String businessNumber);

    boolean existsByStoreName(String storeName);

    void deleteByUserId(UUID userId);
}
