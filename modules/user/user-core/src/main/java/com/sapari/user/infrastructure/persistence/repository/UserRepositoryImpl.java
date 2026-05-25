package com.sapari.user.infrastructure.persistence.repository;

import lombok.RequiredArgsConstructor;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityNotFoundException;

import org.springframework.stereotype.Repository;

import com.sapari.user.domain.model.ProviderType;
import com.sapari.user.domain.model.User;
import com.sapari.user.domain.model.UserRole;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.infrastructure.persistence.entity.UserEntity;
import com.sapari.user.infrastructure.persistence.mapper.UserMapper;

@Repository
@RequiredArgsConstructor
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository userJpaRepository;

    @Override
    public User save(User user) {
        if (user.userId() == null) {
            UserEntity entity = UserMapper.toEntity(user);
            UserEntity saved = userJpaRepository.save(entity);

            return UserMapper.toDomain(saved);
        }

        UserEntity existingEntity = userJpaRepository.findById(user.userId())
                .orElseThrow(() -> new EntityNotFoundException("해당 유저를 찾을 수 없습니다."));

        UserMapper.updateEntityFromDomain(existingEntity, user);

        return UserMapper.toDomain(userJpaRepository.save(existingEntity));
    }

    @Override
    public Optional<User> findById(UUID userId) {
        return userJpaRepository.findById(userId)
                .map(UserMapper::toDomain);
    }

    @Override
    public Optional<User> findByProviderAndProviderId(ProviderType provider, String providerId) {
        return userJpaRepository.findByProviderAndProviderId(provider, providerId)
                .map(UserMapper::toDomain);
    }

    @Override
    public Optional<User> findByEmailAndRole(String email, UserRole role) {
        return userJpaRepository.findByEmailAndRole(email, role)
                .map(UserMapper::toDomain);
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
    public boolean existsByPhoneNumberAndUserIdNot(String phoneNumber, UUID userId) {
        return userJpaRepository.existsByPhoneNumberAndUserIdNot(phoneNumber, userId);
    }

    @Override
    public boolean existsByEmailAndUserIdNot(String email, UUID userId) {
        return userJpaRepository.existsByEmailAndUserIdNot(email, userId);
    }
}
