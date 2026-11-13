package com.sapari.user.application;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sapari.user.application.port.UserEventPublisher;

import com.sapari.global.time.TimeProvider;
import com.sapari.user.command.RegisterSellerCommand;
import com.sapari.user.command.RegisterSocialCustomerCommand;
import com.sapari.user.domain.model.User;
import com.sapari.user.domain.model.WithdrawnUserRetention;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.domain.repository.WithdrawnUserRetentionRepository;
import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserRole;
import com.sapari.user.port.UserAccountUseCase;
import com.sapari.user.view.UserView;

/**
 * UserAccountUseCase 구현. User 애그리거트의 생성·조회·변경을 user-core 안으로 캡슐화하고
 * 외부에는 UserView만 노출한다. 시각은 TimeProvider로 user-core가 채운다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAccountService implements UserAccountUseCase {

    private static final long WITHDRAWN_USER_RETENTION_YEARS = 5L;

    private final UserRepository userRepository;
    private final WithdrawnUserRetentionRepository withdrawnUserRetentionRepository;
    private final WithdrawnUserRetentionMasker withdrawnUserRetentionMasker;
    private final UserEventPublisher userEventPublisher;
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

    /**
     * 회원탈퇴 신청 시 사용자 상태를 WITHDRAWING으로 변경하고 deletedAt에 유예 시작 시각을 기록한다.
     * 원문 개인정보는 남기지 않고 보존 테이블에는 마스킹된 식별 힌트만 한 번 저장한다.
     */
    @Override
    @Transactional
    public UserView requestWithdrawal(UUID userId) {
        Instant now = timeProvider.now();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("user not found: " + userId));

        createRetentionIfAbsent(user, now);

        User updated = user.requestWithdrawal(now);
        UserView view = toView(userRepository.save(updated));

        // 커밋 이후에만 발행 — 롤백 시 오발행(멀쩡한 계정에 구독자가 조치하는 것)을 막는다.
        registerWithdrawalPublish(userId, now);
        return view;
    }

    /**
     * 탈퇴 신청 발행을 커밋 뒤로 미룬다 — 형제 {@code EndLiveService.registerRoomEndedPublish} 와 같은
     * 모양이다. 트랜잭션 밖에서 불리면(회귀) 커밋 보장이 없으므로 발행하지 않고 경고만 남긴다.
     *
     * <p><b>발행이 하는 일</b>: 열려 있는 WebSocket 세션을 구독자가 끊는다. 이 경로는 이미
     * {@code revokeAllSessions} 로 access 토큰을 폐기하지만, <b>이미 수립된 WS 세션은 토큰을 다시
     * 보지 않는다</b> — 폐기가 막는 것은 재입장뿐이다. 그 둘이 다른 축이라 신호가 따로 필요하다.
     */
    private void registerWithdrawalPublish(UUID userId, Instant requestedAt) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("트랜잭션 동기화 비활성 — 탈퇴 신청 발행 생략. userId={}", userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                userEventPublisher.publishWithdrawalRequested(userId, requestedAt);
            }
        });
    }

    /**
     * 동일 사용자에 대한 보존 row가 없을 때만 생성해 탈퇴 신청 재호출에도 중복 보존정보를 만들지 않는다.
     */
    private void createRetentionIfAbsent(User user, Instant now) {
        if (withdrawnUserRetentionRepository.existsByOriginalUserId(user.userId())) {
            return;
        }

        withdrawnUserRetentionRepository.save(WithdrawnUserRetention.create(
                user.userId(),
                withdrawnUserRetentionMasker.maskName(user.name()),
                withdrawnUserRetentionMasker.maskEmail(user.email()),
                withdrawnUserRetentionMasker.maskPhoneNumber(user.phoneNumber()),
                retentionUntil(now),
                now
        ));
    }

    /**
     * 법정 보존 만료 시각을 UTC 기준으로 계산한다.
     */
    private Instant retentionUntil(Instant withdrawalRequestedAt) {
        return ZonedDateTime.ofInstant(withdrawalRequestedAt, ZoneOffset.UTC)
                .plusYears(WITHDRAWN_USER_RETENTION_YEARS)
                .toInstant();
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
