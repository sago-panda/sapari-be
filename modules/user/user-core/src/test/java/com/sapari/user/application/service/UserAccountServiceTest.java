package com.sapari.user.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import com.sapari.user.model.UserGender;
import com.sapari.user.model.UserGrade;
import com.sapari.user.model.UserRole;
import com.sapari.user.model.UserStatus;
import com.sapari.user.view.UserView;
import com.sapari.user.application.support.WithdrawnUserRetentionMasker;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserAccountService 테스트")
class UserAccountServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TimeProvider timeProvider;

    @Mock
    private WithdrawnUserRetentionRepository withdrawnUserRetentionRepository;

    @Mock
    private TermsRepository termsRepository;

    @Mock
    private UserTermsAgreementRepository userTermsAgreementRepository;

    private final WithdrawnUserRetentionMasker withdrawnUserRetentionMasker = new WithdrawnUserRetentionMasker();

    private UserAccountService userAccountService;

    @BeforeEach
    void setUp() {
        userAccountService = new UserAccountService(
                userRepository,
                withdrawnUserRetentionRepository,
                termsRepository,
                userTermsAgreementRepository,
                withdrawnUserRetentionMasker,
                timeProvider
        );
    }

    @Test
    @DisplayName("소셜 고객 가입 시 PRIVACY 동의와 MARKETING 미동의 이력을 저장한다")
    void registerSocialCustomerSavesRequiredPrivacyAndMarketingFalseHistory() {
        // given
        UUID userId = UUID.randomUUID();
        UUID privacyTermsId = UUID.randomUUID();
        UUID marketingTermsId = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-21T01:00:00Z");
        when(timeProvider.now()).thenReturn(now);
        when(termsRepository.findActiveByTypeEffectiveAt(TermsType.PRIVACY, now))
                .thenReturn(Optional.of(terms(privacyTermsId, TermsType.PRIVACY, true)));
        when(termsRepository.findActiveByTypeEffectiveAt(TermsType.MARKETING, now))
                .thenReturn(Optional.of(terms(marketingTermsId, TermsType.MARKETING, false)));
        when(userRepository.save(any(User.class))).thenAnswer(invocation ->
                invocation.<User>getArgument(0).toBuilder().userId(userId).build()
        );
        when(userTermsAgreementRepository.save(any(UserTermsAgreement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        UserView result = userAccountService.registerSocialCustomer(socialCustomerCommand(true, false));

        // then
        assertThat(result.userId()).isEqualTo(userId);
        ArgumentCaptor<UserTermsAgreement> agreementCaptor = ArgumentCaptor.forClass(UserTermsAgreement.class);
        verify(userTermsAgreementRepository, times(2)).save(agreementCaptor.capture());
        assertThat(agreementCaptor.getAllValues())
                .extracting(UserTermsAgreement::termsId, UserTermsAgreement::agreed, UserTermsAgreement::agreedAt)
                .containsExactly(
                        tuple(privacyTermsId, true, now),
                        tuple(marketingTermsId, false, now)
                );
    }

    @Test
    @DisplayName("판매자 가입 시 PRIVACY 동의와 MARKETING 동의 이력을 저장한다")
    void registerSellerSavesRequiredPrivacyAndMarketingTrueHistory() {
        // given
        UUID userId = UUID.randomUUID();
        UUID privacyTermsId = UUID.randomUUID();
        UUID marketingTermsId = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-21T01:00:00Z");
        when(timeProvider.now()).thenReturn(now);
        when(termsRepository.findActiveByTypeEffectiveAt(TermsType.PRIVACY, now))
                .thenReturn(Optional.of(terms(privacyTermsId, TermsType.PRIVACY, true)));
        when(termsRepository.findActiveByTypeEffectiveAt(TermsType.MARKETING, now))
                .thenReturn(Optional.of(terms(marketingTermsId, TermsType.MARKETING, false)));
        when(userRepository.save(any(User.class))).thenAnswer(invocation ->
                invocation.<User>getArgument(0).toBuilder().userId(userId).build()
        );
        when(userTermsAgreementRepository.save(any(UserTermsAgreement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        userAccountService.registerSeller(sellerCommand(true, true));

        // then
        ArgumentCaptor<UserTermsAgreement> agreementCaptor = ArgumentCaptor.forClass(UserTermsAgreement.class);
        verify(userTermsAgreementRepository, times(2)).save(agreementCaptor.capture());
        assertThat(agreementCaptor.getAllValues())
                .extracting(UserTermsAgreement::termsId, UserTermsAgreement::agreed)
                .containsExactly(
                        tuple(privacyTermsId, true),
                        tuple(marketingTermsId, true)
                );
    }

    @Test
    @DisplayName("개인정보 필수 동의가 없으면 사용자와 약관 이력을 저장하지 않는다")
    void registerRejectsPrivacyFalseBeforeSavingUser() {
        assertThatThrownBy(() -> userAccountService.registerSocialCustomer(socialCustomerCommand(false, true)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(userRepository, never()).save(any(User.class));
        verify(userTermsAgreementRepository, never()).save(any(UserTermsAgreement.class));
    }

    @Test
    @DisplayName("활성 PRIVACY 약관이 없으면 가입에 실패한다")
    void registerFailsWhenActivePrivacyTermsMissing() {
        // given
        when(timeProvider.now()).thenReturn(Instant.parse("2026-06-21T01:00:00Z"));
        when(termsRepository.findActiveByTypeEffectiveAt(TermsType.PRIVACY, Instant.parse("2026-06-21T01:00:00Z")))
                .thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> userAccountService.registerSocialCustomer(socialCustomerCommand(true, true)))
                .isInstanceOf(IllegalStateException.class);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("활성 MARKETING 약관이 없으면 가입에 실패한다")
    void registerFailsWhenActiveMarketingTermsMissing() {
        // given
        when(timeProvider.now()).thenReturn(Instant.parse("2026-06-21T01:00:00Z"));
        when(termsRepository.findActiveByTypeEffectiveAt(TermsType.PRIVACY, Instant.parse("2026-06-21T01:00:00Z")))
                .thenReturn(Optional.of(terms(UUID.randomUUID(), TermsType.PRIVACY, true)));
        when(termsRepository.findActiveByTypeEffectiveAt(TermsType.MARKETING, Instant.parse("2026-06-21T01:00:00Z")))
                .thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> userAccountService.registerSocialCustomer(socialCustomerCommand(true, true)))
                .isInstanceOf(IllegalStateException.class);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("탈퇴 신청 시 탈퇴회원 보존 레코드를 마스킹된 최소 정보로 생성한다")
    void requestWithdrawalCreatesMaskedRetentionRecord() {
        // given
        UUID userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-15T09:40:00Z");
        User user = activeCustomer(userId);
        when(timeProvider.now()).thenReturn(now);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(withdrawnUserRetentionRepository.existsByOriginalUserId(userId)).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        UserView result = userAccountService.requestWithdrawal(userId);

        // then
        assertThat(result.status()).isEqualTo(UserStatus.WITHDRAWING);

        ArgumentCaptor<WithdrawnUserRetention> retentionCaptor =
                ArgumentCaptor.forClass(WithdrawnUserRetention.class);
        verify(withdrawnUserRetentionRepository).save(retentionCaptor.capture());
        WithdrawnUserRetention retention = retentionCaptor.getValue();

        assertThat(retention.originalUserId()).isEqualTo(userId);
        assertThat(retention.nameMasked()).isEqualTo("홍*동");
        assertThat(retention.emailMasked()).isEqualTo("te***@example.com");
        assertThat(retention.phoneNumberMasked()).isEqualTo("010****5678");
        assertThat(retention.retentionUntil()).isEqualTo(ZonedDateTime
                .ofInstant(now, ZoneOffset.UTC)
                .plusYears(5)
                .toInstant());
        assertThat(retention.createdAt()).isNull();
        assertThat(retention.purgedAt()).isNull();

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().status()).isEqualTo(UserStatus.WITHDRAWING);
        assertThat(userCaptor.getValue().deletedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("이미 보존 레코드가 있으면 탈퇴 신청 시 중복 생성하지 않는다")
    void requestWithdrawalDoesNotCreateDuplicateRetentionRecord() {
        // given
        UUID userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-15T09:40:00Z");
        User user = activeCustomer(userId);
        when(timeProvider.now()).thenReturn(now);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(withdrawnUserRetentionRepository.existsByOriginalUserId(userId)).thenReturn(true);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        userAccountService.requestWithdrawal(userId);

        // then
        verify(withdrawnUserRetentionRepository, never()).save(any());
    }

    private RegisterSocialCustomerCommand socialCustomerCommand(boolean privacyAgreed, boolean marketingAgreed) {
        return new RegisterSocialCustomerCommand(
                "customer",
                "구매자",
                LocalDate.of(2000, 1, 1),
                UserGender.FEMALE,
                "01012345678",
                "customer@example.com",
                "https://image.example/profile.png",
                privacyAgreed,
                marketingAgreed,
                ProviderType.KAKAO,
                "provider-id",
                "provider@example.com"
        );
    }

    private RegisterSellerCommand sellerCommand(boolean privacyAgreed, boolean marketingAgreed) {
        return new RegisterSellerCommand(
                "seller",
                "판매자",
                "01087654321",
                "seller@example.com",
                privacyAgreed,
                marketingAgreed
        );
    }

    private Terms terms(UUID termsId, TermsType type, boolean required) {
        return Terms.of(
                termsId,
                type,
                "v1.0",
                type + " 약관",
                required,
                "/test/terms/" + type.name().toLowerCase() + "/v1.0",
                "MARKDOWN",
                Instant.parse("2026-06-21T00:00:00Z"),
                true,
                Instant.parse("2026-06-21T00:00:00Z"),
                Instant.parse("2026-06-21T00:00:00Z")
        );
    }

    private User activeCustomer(UUID userId) {
        return User.builder()
                .userId(userId)
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .nickname("tester")
                .nicknameChangedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .name("홍길동")
                .birthDate(LocalDate.of(1995, 5, 15))
                .gender(UserGender.MALE)
                .phoneNumber("01012345678")
                .email("test@example.com")
                .grade(UserGrade.BRONZE)
                .pointBalance(0)
                .marketingAgreed(false)
                .provider(ProviderType.KAKAO)
                .providerId("provider-id")
                .providerEmail("provider@example.com")
                .providerCreatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }
}
