package com.sapari.seller.domain.repository;

import java.util.Optional;
import java.util.UUID;

import com.sapari.seller.domain.model.SellerProfile;

public interface SellerProfileRepository {

    SellerProfile save(SellerProfile sellerProfile);

    Optional<SellerProfile> findByUserId(UUID userId);

    boolean existsByBusinessNumber(String businessNumber);

    boolean existsByStoreName(String storeName);

    void deleteByUserId(UUID userId);
}
