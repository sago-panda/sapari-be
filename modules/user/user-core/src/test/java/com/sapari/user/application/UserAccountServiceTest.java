package com.sapari.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
import com.sapari.user.domain.model.User;
import com.sapari.user.domain.model.WithdrawnUserRetention;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.domain.repository.WithdrawnUserRetentionRepository;
import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserGender;
import com.sapari.user.model.UserGrade;
import com.sapari.user.model.UserRole;
import com.sapari.user.model.UserStatus;
import com.sapari.user.view.UserView;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserAccountService 테스트")
class UserAccountServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TimeProvider timeProvider;

    @Mock
    private WithdrawnUserRetentionRepository withdrawnUserRetentionRepository;

    private final WithdrawnUserRetentionMasker withdrawnUserRetentionMasker = new WithdrawnUserRetentionMasker();

    private UserAccountService userAccountService;

    @BeforeEach
    void setUp() {
        userAccountService = new UserAccountService(
                userRepository,
                withdrawnUserRetentionRepository,
                withdrawnUserRetentionMasker,
                timeProvider
        );
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
