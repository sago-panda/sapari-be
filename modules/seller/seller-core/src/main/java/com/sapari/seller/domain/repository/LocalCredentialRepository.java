package com.sapari.seller.domain.repository;

import java.util.Optional;
import java.util.UUID;

import com.sapari.seller.domain.model.LocalCredential;

public interface LocalCredentialRepository {

    LocalCredential save(LocalCredential localCredential);

    Optional<LocalCredential> findById(UUID userId);

    Optional<LocalCredential> findByIdForUpdate(UUID userId);

    void deleteByUserId(UUID userId);
}
