package com.sapari.user.infrastructure.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserRole;
import com.sapari.user.infrastructure.persistence.entity.UserEntity;

@Repository
public interface UserJpaRepository extends JpaRepository<UserEntity, UUID> {

    /** 동일 사용자 변경을 트랜잭션 종료까지 직렬화한다. 일반 조회에는 잠금을 걸지 않는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UserEntity> findWithLockById(UUID id);

    Optional<UserEntity> findByProviderAndProviderId(ProviderType provider, String providerId);

    Optional<UserEntity> findByEmailAndRole(String email, UserRole role);

    boolean existsByProviderAndProviderId(ProviderType provider, String providerId);

    boolean existsByPhoneNumber(String phoneNumber);

    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);

    boolean existsByPhoneNumberAndIdNot(String phoneNumber, UUID userId);

    boolean existsByEmailAndIdNot(String email, UUID userId);
}
