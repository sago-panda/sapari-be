package com.sapari.seller.infrastructure.persistence.repository;

import lombok.RequiredArgsConstructor;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityNotFoundException;

import org.springframework.stereotype.Repository;

import com.sapari.seller.domain.model.SellerProfile;
import com.sapari.seller.domain.repository.SellerProfileRepository;
import com.sapari.seller.infrastructure.persistence.entity.SellerProfileEntity;
import com.sapari.seller.infrastructure.persistence.mapper.SellerProfileMapper;

@Repository
@RequiredArgsConstructor
public class SellerProfileRepositoryImpl implements SellerProfileRepository {

    private final SellerProfileJpaRepository sellerProfileJpaRepository;

    @Override
    public SellerProfile save(SellerProfile sellerProfile) {
        if (sellerProfile.sellerProfileId() == null) {
            return SellerProfileMapper.toDomain(
                    sellerProfileJpaRepository.save(SellerProfileMapper.toEntity(sellerProfile))
            );
        }

        SellerProfileEntity existingEntity = sellerProfileJpaRepository.findById(sellerProfile.sellerProfileId())
                .orElseThrow(() -> new EntityNotFoundException("해당 판매자 프로필을 찾을 수 없습니다."));

        SellerProfileMapper.updateEntityFromDomain(existingEntity, sellerProfile);

        return SellerProfileMapper.toDomain(sellerProfileJpaRepository.save(existingEntity));
    }

    @Override
    public Optional<SellerProfile> findByUserId(UUID userId) {
        return sellerProfileJpaRepository.findByUserId(userId)
                .map(SellerProfileMapper::toDomain);
    }

    @Override
    public boolean existsByBusinessNumber(String businessNumber) {
        return sellerProfileJpaRepository.existsByBusinessNumber(businessNumber);
    }

    @Override
    public boolean existsByStoreName(String storeName) {
        return sellerProfileJpaRepository.existsByStoreName(storeName);
    }
}
