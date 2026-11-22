package com.sapari.user.domain.repository;

import java.util.Optional;
import java.util.UUID;

import com.sapari.user.model.ProviderType;
import com.sapari.user.domain.model.User;
import com.sapari.user.model.UserRole;

public interface UserRepository {

    User save(User user);

    Optional<User> findById(UUID userId);

    /** 변경 트랜잭션에서 사용자 행을 잠그고 최신 상태를 조회한다. */
    Optional<User> findByIdForUpdate(UUID userId);

    Optional<User> findByProviderAndProviderId(ProviderType provider, String providerId);

    Optional<User> findByEmailAndRole(String email, UserRole role);

    boolean existsByProviderAndProviderId(ProviderType provider, String providerId);

    boolean existsByPhoneNumber(String phoneNumber);

    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);

    boolean existsByPhoneNumberAndUserIdNot(String phoneNumber, UUID userId);

    boolean existsByEmailAndUserIdNot(String email, UUID userId);

    void deleteById(UUID userId);
}
