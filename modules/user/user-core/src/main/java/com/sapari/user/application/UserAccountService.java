package com.sapari.user.application;

import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.global.time.TimeProvider;
import com.sapari.user.command.RegisterSellerCommand;
import com.sapari.user.command.RegisterSocialCustomerCommand;
import com.sapari.user.domain.model.Terms;
import com.sapari.user.domain.model.User;
import com.sapari.user.domain.model.UserTermsAgreement;
import com.sapari.user.domain.model.WithdrawnUserRetention;
import com.sapari.user.domain.repository.TermsRepository;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.domain.repository.UserTermsAgreementRepository;
import com.sapari.user.domain.repository.WithdrawnUserRetentionRepository;
import com.sapari.user.model.ProviderType;
import com.sapari.user.model.TermsType;
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

    private static final long WITHDRAWN_USER_RETENTION_YEARS = 5L;

    private final UserRepository userRepository;
    private final WithdrawnUserRetentionRepository withdrawnUserRetentionRepository;
    private final TermsRepository termsRepository;
    private final UserTermsAgreementRepository userTermsAgreementRepository;
    private final WithdrawnUserRetentionMasker withdrawnUserRetentionMasker;
    private final TimeProvider timeProvider;

    /**
     * 구매자 가입과 약관 증적 저장을 하나의 트랜잭션 성공 조건으로 처리한다.
     */
    @Override
    @Transactional
    public UserView registerSocialCustomer(RegisterSocialCustomerCommand command) {
        Instant now = timeProvider.now();
        validateRequiredPrivacyAgreement(command.privacyAgreed());
        Terms privacyTerms = findActiveTerms(TermsType.PRIVACY, now);
        Terms marketingTerms = findActiveTerms(TermsType.MARKETING, now);
        User user = createSocialCustomer(command, now);
        User savedUser = userRepository.save(user);
        saveSignupTermsAgreements(savedUser.userId(), privacyTerms, marketingTerms, command.marketingAgreed(), now);
        return toView(savedUser);
    }

    private User createSocialCustomer(RegisterSocialCustomerCommand command, Instant now) {
        return User.createSocialCustomer(
                command.nickname(),
                command.name(),
                command.birthDate(),
                command.gender(),
                command.phoneNumber(),
                command.email(),
                command.profileImageUrl(),
                command.marketingAgreed(),
                command.provider(),
                command.providerId(),
                command.providerEmail(),
                now,
                now
        );
    }

    /**
     * 판매자 가입과 약관 증적 저장을 하나의 트랜잭션 성공 조건으로 처리한다.
     */
    @Override
    @Transactional
    public UserView registerSeller(RegisterSellerCommand command) {
        Instant now = timeProvider.now();
        validateRequiredPrivacyAgreement(command.privacyAgreed());
        Terms privacyTerms = findActiveTerms(TermsType.PRIVACY, now);
        Terms marketingTerms = findActiveTerms(TermsType.MARKETING, now);
        User user = createSeller(command, now);
        User savedUser = userRepository.save(user);
        saveSignupTermsAgreements(savedUser.userId(), privacyTerms, marketingTerms, command.marketingAgreed(), now);
        return toView(savedUser);
    }

    private User createSeller(RegisterSellerCommand command, Instant now) {
        return User.createSeller(
                command.nickname(),
                command.name(),
                command.phoneNumber(),
                command.email(),
                command.marketingAgreed(),
                now
        );
    }

    /**
     * 회원가입 시점의 활성 약관 버전과 사용자 선택값을 같은 트랜잭션의 증적 이력으로 고정한다.
     * MARKETING은 선택 약관이므로 false도 명시적 거부 증적으로 저장한다.
     */
    private void saveSignupTermsAgreements(
            UUID userId,
            Terms privacyTerms,
            Terms marketingTerms,
            boolean marketingAgreed,
            Instant agreedAt
    ) {
        // 필수 약관 저장
        userTermsAgreementRepository.save(UserTermsAgreement.create(
                userId,
                privacyTerms.termsId(),
                true,
                agreedAt
        ));

        // 마케팅 약관 저장
        userTermsAgreementRepository.save(UserTermsAgreement.create(
                userId,
                marketingTerms.termsId(),
                marketingAgreed,
                agreedAt
        ));
    }

    /**
     * HTTP DTO 검증을 거치지 않는 호출자도 필수 개인정보 동의 불변식을 우회하지 못하게 막는다.
     */
    private void validateRequiredPrivacyAgreement(boolean privacyAgreed) {
        if (!privacyAgreed) {
            throw new IllegalArgumentException("개인정보 수집 및 이용 동의는 필수입니다.");
        }
    }

    /**
     * 가입 증적은 가입 시각에 이미 효력이 시작된 활성 약관 버전을 참조해야 한다.
     * active=true는 현재 유효 약관 1개라는 운영 상태이고, effectiveAt 조건은 미래 약관 오등록을 방어한다.
     * 선택 약관인 MARKETING도 거부 증적을 버전과 함께 남겨야 하므로 누락 시 설정 오류로 간주한다.
     */
    private Terms findActiveTerms(TermsType type, Instant effectiveAt) {
        return termsRepository.findActiveByTypeEffectiveAt(type, effectiveAt)
                .orElseThrow(() -> new IllegalStateException("active terms not found: " + type));
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
        return toView(userRepository.save(updated));
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
                retentionUntil(now)
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
