package com.sapari.user.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.sapari.user.command.ProfileImageChangeCommand;
import com.sapari.user.command.RegisterSellerCommand;
import com.sapari.user.command.RegisterSocialCustomerCommand;
import com.sapari.user.application.dto.ProfileImageChangeResult;
import com.sapari.user.application.dto.ProfileImageRemoveResult;
import com.sapari.user.application.dto.ProfileImageStoreCommand;
import com.sapari.user.application.dto.StoredProfileImage;
import com.sapari.user.application.port.ProfileImageStorage;
import com.sapari.user.application.support.ProfileImageUploadValidator;
import com.sapari.user.domain.model.Terms;
import com.sapari.user.domain.model.User;
import com.sapari.user.domain.model.UserTermsAgreement;
import com.sapari.user.domain.model.WithdrawnUserRetention;
import com.sapari.user.domain.repository.TermsRepository;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.domain.repository.UserTermsAgreementRepository;
import com.sapari.user.domain.repository.WithdrawnUserRetentionRepository;
import com.sapari.user.domain.exception.UserErrorCode;
import com.sapari.user.domain.exception.UserException;
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

    @Mock
    private ProfileImageUploadValidator profileImageUploadValidator;

    @Mock
    private ProfileImageStorage profileImageStorage;

    @Mock
    private ProfileImageMutationProcessor profileImageMutationProcessor;

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
                profileImageUploadValidator,
                profileImageStorage,
                profileImageMutationProcessor,
                key -> key == null ? null : "https://cdn.example/" + key,
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
    @DisplayName("UserView에는 내부 프로필 이미지 key가 아니라 공개 URL을 반환한다")
    void findByIdResolvesProfileImageKeyToUrl() {
        // given
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeCustomer(userId)));

        // when
        UserView result = userAccountService.findById(userId).orElseThrow();

        // then
        assertThat(result.profileImageUrl())
                .isEqualTo("https://cdn.example/users/%s/profile/image.jpg".formatted(userId));
    }

    @Test
    @DisplayName("프로필 이미지 변경 시 검증된 이미지를 저장하고 새 key를 사용자에게 반영한다")
    void changeProfileImageStoresValidatedImageAndUpdatesUserProfileImageKey() {
        // given
        UUID userId = UUID.randomUUID();
        User user = activeCustomer(userId);
        String oldKey = user.profileImageKey();
        String newKey = "users/%s/profile/new-image.png".formatted(userId);
        ProfileImageChangeCommand command = profileImageChangeCommand(userId);
        ProfileImageStoreCommand storeCommand = new ProfileImageStoreCommand(
                userId,
                "png",
                "image/png",
                new byte[] {1, 2, 3}
        );
        when(profileImageUploadValidator.validate(
                userId,
                command.originalFilename(),
                command.contentType(),
                command.content()
        )).thenReturn(storeCommand);
        when(profileImageStorage.store(storeCommand)).thenReturn(new StoredProfileImage(newKey, "image/png", 3));
        when(profileImageMutationProcessor.replaceProfileImageKey(userId, newKey))
                .thenReturn(new ProfileImageChangeResult(user.updateProfileImageKey(newKey), oldKey));

        // when
        UserView result = userAccountService.changeProfileImage(command);

        // then
        assertThat(result.profileImageUrl()).isEqualTo("https://cdn.example/" + newKey);
        verify(profileImageMutationProcessor).replaceProfileImageKey(userId, newKey);
        verify(profileImageStorage).deleteQuietly(oldKey);
    }

    @Test
    @DisplayName("프로필 이미지 검증 실패 시 저장소와 DB 저장을 호출하지 않는다")
    void changeProfileImageDoesNotStoreWhenValidationFails() {
        // given
        UUID userId = UUID.randomUUID();
        ProfileImageChangeCommand command = profileImageChangeCommand(userId);
        when(profileImageUploadValidator.validate(
                userId,
                command.originalFilename(),
                command.contentType(),
                command.content()
        )).thenThrow(new UserException(UserErrorCode.PROFILE_IMAGE_INVALID_CONTENT));

        // when, then
        assertThatThrownBy(() -> userAccountService.changeProfileImage(command))
                .isInstanceOf(UserException.class);
        verifyNoInteractions(profileImageStorage);
        verifyNoInteractions(profileImageMutationProcessor);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("프로필 이미지 저장소 업로드 실패 시 DB 저장을 호출하지 않는다")
    void changeProfileImageDoesNotSaveUserWhenStorageUploadFails() {
        // given
        UUID userId = UUID.randomUUID();
        ProfileImageChangeCommand command = profileImageChangeCommand(userId);
        ProfileImageStoreCommand storeCommand = new ProfileImageStoreCommand(userId, "png", "image/png", new byte[] {1});
        when(profileImageUploadValidator.validate(
                userId,
                command.originalFilename(),
                command.contentType(),
                command.content()
        )).thenReturn(storeCommand);
        when(profileImageStorage.store(storeCommand)).thenThrow(new IllegalStateException("storage down"));

        // when, then
        assertThatThrownBy(() -> userAccountService.changeProfileImage(command))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(profileImageMutationProcessor);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("프로필 이미지 DB 저장 실패 시 새 object를 보상 삭제하고 원래 예외를 전파한다")
    void changeProfileImageDeletesNewObjectWhenUserSaveFails() {
        // given
        UUID userId = UUID.randomUUID();
        String newKey = "users/%s/profile/new-image.png".formatted(userId);
        ProfileImageChangeCommand command = profileImageChangeCommand(userId);
        ProfileImageStoreCommand storeCommand = new ProfileImageStoreCommand(userId, "png", "image/png", new byte[] {1});
        IllegalStateException dbException = new IllegalStateException("db down");
        when(profileImageUploadValidator.validate(
                userId,
                command.originalFilename(),
                command.contentType(),
                command.content()
        )).thenReturn(storeCommand);
        when(profileImageStorage.store(storeCommand)).thenReturn(new StoredProfileImage(newKey, "image/png", 1));
        when(profileImageMutationProcessor.replaceProfileImageKey(userId, newKey)).thenThrow(dbException);

        // when, then
        assertThatThrownBy(() -> userAccountService.changeProfileImage(command))
                .isSameAs(dbException);
        verify(profileImageStorage).deleteQuietly(newKey);
    }

    @Test
    @DisplayName("프로필 이미지 삭제 시 DB key를 비우고 기존 object를 best-effort로 삭제한다")
    void removeProfileImageClearsProfileImageKeyAndDeletesOldObject() {
        // given
        UUID userId = UUID.randomUUID();
        User user = activeCustomer(userId);
        String oldKey = user.profileImageKey();
        when(profileImageMutationProcessor.removeProfileImageKey(userId))
                .thenReturn(new ProfileImageRemoveResult(user.removeProfileImage(), oldKey));

        // when
        UserView result = userAccountService.removeProfileImage(userId);

        // then
        assertThat(result.profileImageUrl()).isNull();
        verify(profileImageMutationProcessor).removeProfileImageKey(userId);
        verify(profileImageStorage).deleteQuietly(oldKey);
    }

    @Test
    @DisplayName("프로필 이미지 삭제 DB 처리 실패 시 기존 object를 삭제하지 않는다")
    void removeProfileImageDoesNotDeleteOldObjectWhenDbUpdateFails() {
        // given
        UUID userId = UUID.randomUUID();
        IllegalStateException dbException = new IllegalStateException("db down");
        when(profileImageMutationProcessor.removeProfileImageKey(userId)).thenThrow(dbException);

        // when, then
        assertThatThrownBy(() -> userAccountService.removeProfileImage(userId))
                .isSameAs(dbException);
        verifyNoInteractions(profileImageStorage);
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

    private ProfileImageChangeCommand profileImageChangeCommand(UUID userId) {
        return new ProfileImageChangeCommand(
                userId,
                "profile.png",
                "image/png",
                new byte[] {1, 2, 3}
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
                .profileImageKey("users/%s/profile/image.jpg".formatted(userId))
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

