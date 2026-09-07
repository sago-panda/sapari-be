package com.sapari.user.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sapari.global.time.TimeProvider;
import com.sapari.user.domain.model.User;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.domain.repository.WithdrawnUserRetentionRepository;
import com.sapari.user.model.UserRole;
import com.sapari.user.model.UserStatus;

class UserMutationPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    /** 첫 변경 직후의 최신 시각을 기준으로 두 번째 닉네임 변경을 거부해야 한다. */
    @Test
    void nicknameRechecksLatestChangeTime() {
        User user = user();
        var current = new java.util.concurrent.atomic.AtomicReference<>(user);
        UserRepository repository = mock(UserRepository.class);
        when(repository.findByIdForUpdate(user.userId())).thenAnswer(call -> Optional.of(current.get()));
        when(repository.save(any(User.class))).thenAnswer(call -> {
            current.set(call.getArgument(0));
            return current.get();
        });
        var service = new UserAccountService(repository, null, null, null, null, null, null, null, null,
                key -> key, new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)));
        service.changeNickname(user.userId(), "first", java.time.Duration.ofDays(30));
        assertThatThrownBy(() -> service.changeNickname(user.userId(), "second", java.time.Duration.ofDays(30)))
                .isInstanceOf(com.sapari.user.exception.NicknameChangeRestrictedException.class);
        assertThat(current.get().nickname()).isEqualTo("first");
    }

    /** 사전 인증 이후 탈퇴한 사용자의 이미지 변경을 mutation 경계에서 거부한다. */
    @Test
    void refusesImageChangeAfterWithdrawal() {
        User user = user().requestWithdrawal(NOW);
        UserRepository repository = repository(user);
        assertThatThrownBy(() -> new ProfileImageMutationProcessor(repository)
                .replaceProfileImageKey(user.userId(), "new-key"))
                .isInstanceOf(com.sapari.user.domain.exception.UserException.class);
    }

    /** 탈퇴 재요청은 최초 유예 시작 시각을 연장하지 않는다. */
    @Test
    void withdrawalRetryKeepsOriginalTimestamp() {
        Instant original = NOW.minusSeconds(60);
        User user = user().requestWithdrawal(original);
        UserRepository repository = repository(user);
        var service = new UserAccountService(repository, mock(WithdrawnUserRetentionRepository.class), null, null,
                new com.sapari.user.application.support.WithdrawnUserRetentionMasker(), null, null, null, null,
                key -> key, new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)));
        assertThat(service.requestWithdrawal(user.userId()).status()).isEqualTo(UserStatus.WITHDRAWING);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any(User.class));
    }

    /** 저장소만 대체하고 실제 domain mutation의 상태 결과를 검증한다. */
    private UserRepository repository(User user) {
        UserRepository repository = mock(UserRepository.class);
        when(repository.findByIdForUpdate(user.userId())).thenReturn(Optional.of(user));
        when(repository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
        return repository;
    }

    /** 상태 전이에 필요한 최소한의 활성 사용자 fixture를 만든다. */
    private User user() {
        return User.builder().userId(UUID.randomUUID()).role(UserRole.USER).status(UserStatus.ACTIVE)
                .nickname("before").nicknameChangedAt(NOW.minusSeconds(86400 * 60L)).build();
    }
}
