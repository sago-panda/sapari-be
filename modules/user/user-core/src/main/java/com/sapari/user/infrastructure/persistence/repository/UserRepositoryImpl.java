package com.sapari.user.infrastructure.persistence.repository;

import lombok.RequiredArgsConstructor;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.stereotype.Repository;

import com.sapari.user.model.ProviderType;
import com.sapari.user.domain.model.User;
import com.sapari.user.model.UserRole;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.infrastructure.persistence.entity.UserEntity;
import com.sapari.user.infrastructure.persistence.mapper.UserMapper;

@Repository
@RequiredArgsConstructor
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository userJpaRepository;
    private final UserMapper userMapper;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${user.mutation.lock-timeout-ms:2000}")
    private int lockTimeoutMillis = 2000;

    /** 실제 PostgreSQL 대기 상한을 설정하고 변경용 사용자 잠금을 획득한다. */
    @Override
    public Optional<User> findByIdForUpdate(UUID userId) {
        if (!entityManager.isJoinedToTransaction()) {
            throw new IllegalStateException("사용자 잠금 조회는 변경 트랜잭션 안에서 호출해야 합니다.");
        }
        if (lockTimeoutMillis <= 0) {
            throw new IllegalStateException("user.mutation.lock-timeout-ms must be positive");
        }
        // JPA 숫자 timeout 힌트 대신 현재 트랜잭션에만 적용되는 PostgreSQL 설정을 사용한다.
        entityManager.createNativeQuery("select set_config('lock_timeout', :timeout, true)")
                .setParameter("timeout", lockTimeoutMillis + "ms")
                .getSingleResult();
        return userJpaRepository.findWithLockById(userId).map(entity -> {
            // OSIV·선행 조회로 L1 캐시에 남은 값은 잠금만으로 갱신되지 않는다. 잠금 보유 중 다시 읽는다.
            entityManager.refresh(entity);
            return userMapper.toDomain(entity);
        });
    }

    @Override
    public User save(User user) {
        if (user.userId() == null) {
            UserEntity entity = userMapper.toEntity(user);
            UserEntity saved = userJpaRepository.save(entity);

            return userMapper.toDomain(saved);
        }

        UserEntity existingEntity = userJpaRepository.findById(user.userId())
                .orElseThrow(() -> new EntityNotFoundException("해당 유저를 찾을 수 없습니다."));

        userMapper.updateEntityFromDomain(existingEntity, user);

        return userMapper.toDomain(userJpaRepository.save(existingEntity));
    }

    @Override
    public Optional<User> findById(UUID userId) {
        return userJpaRepository.findById(userId)
                .map(userMapper::toDomain);
    }

    @Override
    public Optional<User> findByProviderAndProviderId(ProviderType provider, String providerId) {
        return userJpaRepository.findByProviderAndProviderId(provider, providerId)
                .map(userMapper::toDomain);
    }

    @Override
    public Optional<User> findByEmailAndRole(String email, UserRole role) {
        return userJpaRepository.findByEmailAndRole(email, role)
                .map(userMapper::toDomain);
    }

    @Override
    public boolean existsByProviderAndProviderId(ProviderType provider, String providerId) {
        return userJpaRepository.existsByProviderAndProviderId(provider, providerId);
    }

    @Override
    public boolean existsByPhoneNumber(String phoneNumber) {
        return userJpaRepository.existsByPhoneNumber(phoneNumber);
    }

    @Override
    public boolean existsByEmail(String email) {
        return userJpaRepository.existsByEmail(email);
    }

    @Override
    public boolean existsByNickname(String nickname) {
        return userJpaRepository.existsByNickname(nickname);
    }

    @Override
    public boolean existsByPhoneNumberAndUserIdNot(String phoneNumber, UUID userId) {
        return userJpaRepository.existsByPhoneNumberAndIdNot(phoneNumber, userId);
    }

    @Override
    public boolean existsByEmailAndUserIdNot(String email, UUID userId) {
        return userJpaRepository.existsByEmailAndIdNot(email, userId);
    }

    @Override
    public void deleteById(UUID userId) {
        userJpaRepository.deleteById(userId);
    }
}
