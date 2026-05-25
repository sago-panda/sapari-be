package com.sapari.seller.infrastructure.persistence.repository;

import lombok.RequiredArgsConstructor;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.sapari.seller.domain.model.LocalCredential;
import com.sapari.seller.domain.repository.LocalCredentialRepository;
import com.sapari.seller.infrastructure.persistence.mapper.LocalCredentialMapper;

@Repository
@RequiredArgsConstructor
public class LocalCredentialRepositoryImpl implements LocalCredentialRepository {

    private final LocalCredentialJpaRepository localCredentialJpaRepository;

    @Override
    public LocalCredential save(LocalCredential localCredential) {
        return LocalCredentialMapper.toDomain(
                localCredentialJpaRepository.save(LocalCredentialMapper.toEntity(localCredential))
        );
    }

    @Override
    public Optional<LocalCredential> findById(UUID userId) {
        return localCredentialJpaRepository.findById(userId)
                .map(LocalCredentialMapper::toDomain);
    }
}
