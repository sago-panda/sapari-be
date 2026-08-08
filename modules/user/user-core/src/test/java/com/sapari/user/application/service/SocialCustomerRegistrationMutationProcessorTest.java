package com.sapari.user.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.user.command.SocialCustomerRegistrationRollbackCommand;
import com.sapari.user.domain.model.User;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.domain.repository.UserTermsAgreementRepository;
import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserGender;
import com.sapari.user.model.UserGrade;
import com.sapari.user.model.UserRole;
import com.sapari.user.model.UserStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("소셜 고객 가입 보상 processor 테스트")
class SocialCustomerRegistrationMutationProcessorTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserTermsAgreementRepository userTermsAgreementRepository;

    @Test
    @DisplayName("방금 가입한 소셜 고객 식별자가 모두 일치하면 약관 증적 뒤 user를 삭제하고 이미지 key를 반환한다")
    void rollbackDeletesRegistrationDataWhenIdentityMatches() {
        UUID userId = UUID.randomUUID();
        String profileImageKey = "users/%s/profile/signup.png".formatted(userId);
        SocialCustomerRegistrationRollbackCommand command = command(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                customer(userId).updateProfileImageKey(profileImageKey)));
        SocialCustomerRegistrationMutationProcessor processor =
                new SocialCustomerRegistrationMutationProcessor(userRepository, userTermsAgreementRepository);

        String result = processor.rollback(command);

        assertThat(result).isEqualTo(profileImageKey);
        InOrder inOrder = inOrder(userTermsAgreementRepository, userRepository);
        inOrder.verify(userTermsAgreementRepository).deleteByUserId(userId);
        inOrder.verify(userRepository).deleteById(userId);
    }

    @Test
    @DisplayName("프로필 이미지가 없는 소셜 고객 가입 보상은 null key를 반환한다")
    void rollbackReturnsNullWhenRegistrationHasNoProfileImage() {
        UUID userId = UUID.randomUUID();
        SocialCustomerRegistrationRollbackCommand command = command(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customer(userId)));
        SocialCustomerRegistrationMutationProcessor processor =
                new SocialCustomerRegistrationMutationProcessor(userRepository, userTermsAgreementRepository);

        String result = processor.rollback(command);

        assertThat(result).isNull();
        verify(userTermsAgreementRepository).deleteByUserId(userId);
        verify(userRepository).deleteById(userId);
    }

    @Test
    @DisplayName("provider 식별자가 다르면 가입 보상 삭제를 거부한다")
    void rollbackRejectsMismatchedIdentity() {
        UUID userId = UUID.randomUUID();
        SocialCustomerRegistrationRollbackCommand command = command(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                customer(userId).toBuilder().providerId("different-provider-id").build()));
        SocialCustomerRegistrationMutationProcessor processor =
                new SocialCustomerRegistrationMutationProcessor(userRepository, userTermsAgreementRepository);

        assertThatThrownBy(() -> processor.rollback(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("registration identity mismatch");

        verify(userTermsAgreementRepository, never()).deleteByUserId(userId);
        verify(userRepository, never()).deleteById(userId);
    }

    private SocialCustomerRegistrationRollbackCommand command(UUID userId) {
        return new SocialCustomerRegistrationRollbackCommand(
                userId,
                ProviderType.KAKAO,
                "provider-id",
                "customer@example.com"
        );
    }

    private User customer(UUID userId) {
        return User.builder()
                .userId(userId)
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .nickname("customer")
                .nicknameChangedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .name("홍길동")
                .birthDate(LocalDate.of(1995, 5, 15))
                .gender(UserGender.MALE)
                .phoneNumber("01012345678")
                .email("customer@example.com")
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
