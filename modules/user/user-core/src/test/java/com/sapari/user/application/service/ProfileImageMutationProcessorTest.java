package com.sapari.user.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.user.application.dto.ProfileImageChangeResult;
import com.sapari.user.application.dto.ProfileImageRemoveResult;
import com.sapari.user.domain.model.User;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserGender;
import com.sapari.user.model.UserGrade;
import com.sapari.user.model.UserRole;
import com.sapari.user.model.UserStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("프로필 이미지 DB 변경 processor 테스트")
class ProfileImageMutationProcessorTest {

    @Mock
    private UserRepository userRepository;

    @Test
    @DisplayName("프로필 이미지 key 교체 시 기존 key를 반환하고 새 key를 저장한다")
    void replaceProfileImageKeyReturnsOldKeyAndSavesNewKey() {
        // given
        UUID userId = UUID.randomUUID();
        User user = activeCustomer(userId);
        String oldKey = user.profileImageKey();
        String newKey = "users/%s/profile/new-image.png".formatted(userId);
        ProfileImageMutationProcessor processor = new ProfileImageMutationProcessor(userRepository);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        ProfileImageChangeResult result = processor.replaceProfileImageKey(userId, newKey);

        // then
        assertThat(result.oldProfileImageKey()).isEqualTo(oldKey);
        assertThat(result.savedUser().profileImageKey()).isEqualTo(newKey);
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().profileImageKey()).isEqualTo(newKey);
    }

    @Test
    @DisplayName("프로필 이미지 key 제거 시 기존 key를 반환하고 DB key를 비운다")
    void removeProfileImageKeyReturnsOldKeyAndClearsProfileImageKey() {
        // given
        UUID userId = UUID.randomUUID();
        User user = activeCustomer(userId);
        String oldKey = user.profileImageKey();
        ProfileImageMutationProcessor processor = new ProfileImageMutationProcessor(userRepository);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        ProfileImageRemoveResult result = processor.removeProfileImageKey(userId);

        // then
        assertThat(result.oldProfileImageKey()).isEqualTo(oldKey);
        assertThat(result.savedUser().profileImageKey()).isNull();
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().profileImageKey()).isNull();
    }

    @Test
    @DisplayName("사용자가 없으면 프로필 이미지 key 교체를 저장하지 않는다")
    void replaceProfileImageKeyFailsWhenUserMissing() {
        // given
        UUID userId = UUID.randomUUID();
        ProfileImageMutationProcessor processor = new ProfileImageMutationProcessor(userRepository);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> processor.replaceProfileImageKey(userId, "users/%s/profile/new.png".formatted(userId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("user not found");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("사용자가 없으면 프로필 이미지 key 제거를 저장하지 않는다")
    void removeProfileImageKeyFailsWhenUserMissing() {
        // given
        UUID userId = UUID.randomUUID();
        ProfileImageMutationProcessor processor = new ProfileImageMutationProcessor(userRepository);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> processor.removeProfileImageKey(userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("user not found");
        verify(userRepository, never()).save(any(User.class));
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
