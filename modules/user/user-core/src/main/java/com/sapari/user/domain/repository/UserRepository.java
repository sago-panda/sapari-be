package com.sapari.user.domain.repository;

import java.util.Optional;
import java.util.UUID;

import com.sapari.user.domain.model.ProviderType;
import com.sapari.user.domain.model.User;
import com.sapari.user.domain.model.UserRole;

public interface UserRepository {

    User save(User user);

    Optional<User> findById(UUID userId);

    Optional<User> findByProviderAndProviderId(ProviderType provider, String providerId);

    Optional<User> findByEmailAndRole(String email, UserRole role);

    boolean existsByProviderAndProviderId(ProviderType provider, String providerId);

    boolean existsByPhoneNumber(String phoneNumber);

    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);

    boolean existsByPhoneNumberAndUserIdNot(String phoneNumber, UUID userId);

    boolean existsByEmailAndUserIdNot(String email, UUID userId);

    boolean existsByNicknameAndUserIdNot(String nickname, UUID userId);
}
