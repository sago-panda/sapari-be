package com.sapari.seller.infrastructure.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.sapari.seller.infrastructure.persistence.entity.LocalCredentialEntity;

public interface LocalCredentialJpaRepository extends JpaRepository<LocalCredentialEntity, UUID> {

    // 로그인 실패 횟수와 잠금 상태 갱신을 계정 단위로 직렬화한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LocalCredentialEntity> findWithLockByUserId(UUID userId);
}
