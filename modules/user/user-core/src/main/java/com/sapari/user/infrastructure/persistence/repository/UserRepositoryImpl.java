package com.sapari.user.infrastructure.persistence.repository;

import lombok.RequiredArgsConstructor;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityNotFoundException;

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
