package com.sapari.user.application;

import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.global.time.TimeProvider;
import com.sapari.user.command.RegisterSellerCommand;
import com.sapari.user.command.RegisterSocialCustomerCommand;
import com.sapari.user.domain.model.User;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserRole;
import com.sapari.user.port.UserAccountUseCase;
import com.sapari.user.view.UserView;

/**
 * UserAccountUseCase 구현. User 애그리거트의 생성·조회·변경을 user-core 안으로 캡슐화하고
 * 외부에는 UserView만 노출한다. 시각은 TimeProvider로 user-core가 채운다.
 */
@Service
@RequiredArgsConstructor
public class UserAccountService implements UserAccountUseCase {

    private final UserRepository userRepository;
    private final TimeProvider timeProvider;

    @Override
    @Transactional
    public UserView registerSocialCustomer(RegisterSocialCustomerCommand command) {
        Instant now = timeProvider.now();
        User user = User.createSocialCustomer(
                command.nickname(),
                command.name(),
                command.birthDate(),
                command.gender(),
                command.phoneNumber(),
                command.email(),
                command.marketingAgreed(),
                command.provider(),
                command.providerId(),
                command.providerEmail(),
                now,
                now
        );
        return toView(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserView registerSeller(RegisterSellerCommand command) {
        Instant now = timeProvider.now();
        User user = User.createSeller(
                command.nickname(),
                command.name(),
                command.birthDate(),
                command.phoneNumber(),
                command.email(),
                command.marketingAgreed(),
                now
        );
        return toView(userRepository.save(user));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserView> findById(UUID userId) {
        return userRepository.findById(userId).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserView> findBySocialAccount(ProviderType provider, String providerId) {
        return userRepository.findByProviderAndProviderId(provider, providerId).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserView> findByEmailAndRole(String email, UserRole role) {
        return userRepository.findByEmailAndRole(email, role).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByPhoneNumber(String phoneNumber) {
        return userRepository.existsByPhoneNumber(phoneNumber);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByNickname(String nickname) {
        return userRepository.existsByNickname(nickname);
    }

    @Override
    @Transactional
    public UserView changeNickname(UUID userId, String nickname) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("user not found: " + userId));
        User updated = user.updateNickname(nickname, timeProvider.now());
        return toView(userRepository.save(updated));
    }

    @Override
    @Transactional
    public UserView requestWithdrawal(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("user not found: " + userId));
        User updated = user.requestWithdrawal(timeProvider.now());
        return toView(userRepository.save(updated));
    }

    private UserView toView(User user) {
        return new UserView(
                user.userId(),
                user.role(),
                user.status(),
                user.nickname(),
                user.nicknameChangedAt(),
                user.name(),
                user.birthDate(),
                user.gender(),
                user.phoneNumber(),
                user.profileImageKey(),
                user.email(),
                user.grade(),
                user.pointBalance(),
                user.marketingAgreed(),
                user.provider(),
                user.providerId(),
                user.providerEmail()
        );
    }
}
