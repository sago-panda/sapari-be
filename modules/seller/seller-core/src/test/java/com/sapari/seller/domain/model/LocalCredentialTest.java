package com.sapari.seller.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("로컬 로그인 인증 정보 테스트")
class LocalCredentialTest {

    @Test
    @DisplayName("사용자 ID와 비밀번호 해시로 로컬 인증 정보를 생성한다")
    void createReturnsLocalCredential() {
        // given
        UUID userId = UUID.randomUUID();
        Instant lastChangedAt = lastChangedAt();

        // when
        LocalCredential localCredential = LocalCredential.create(userId, "hashed-password", lastChangedAt);

        // then
        assertThat(localCredential.userId()).isEqualTo(userId);
        assertThat(localCredential.passwordHash()).isEqualTo("hashed-password");
        assertThat(localCredential.failedLoginCount()).isZero();
        assertThat(localCredential.lockedAt()).isNull();
        assertThat(localCredential.lastChangedAt()).isEqualTo(lastChangedAt);
    }

    @Test
    @DisplayName("사용자 ID가 없으면 생성에 실패한다")
    void createThrowsExceptionWhenUserIdIsNull() {
        assertThatThrownBy(() -> LocalCredential.create(null, "hashed-password", lastChangedAt()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("비밀번호 해시가 없으면 생성에 실패한다")
    void createThrowsExceptionWhenPasswordHashIsBlank() {
        assertThatThrownBy(() -> LocalCredential.create(UUID.randomUUID(), " ", lastChangedAt()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("잠금 임계치가 1보다 작으면 로그인 실패 기록에 실패한다")
    void recordLoginFailureThrowsExceptionWhenLockThresholdIsNotPositive() {
        LocalCredential localCredential = LocalCredential.create(
                UUID.randomUUID(),
                "hashed-password",
                lastChangedAt()
        );

        assertThatThrownBy(() -> localCredential.recordLoginFailure(lastChangedAt(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("로그인 실패 횟수나 잠금 시각이 있으면 실패 이력이 있다고 본다")
    void hasLoginFailureHistoryReturnsTrueWhenFailureCountOrLockExists() {
        UUID userId = UUID.randomUUID();
        LocalCredential failedCredential = new LocalCredential(
                userId,
                "hashed-password",
                1,
                null,
                lastChangedAt()
        );
        LocalCredential lockedCredential = new LocalCredential(
                userId,
                "hashed-password",
                0,
                lastChangedAt(),
                lastChangedAt()
        );

        assertThat(failedCredential.hasLoginFailureHistory()).isTrue();
        assertThat(lockedCredential.hasLoginFailureHistory()).isTrue();
    }

    @Test
    @DisplayName("로그인 실패 횟수와 잠금 시각이 없으면 실패 이력이 없다고 본다")
    void hasLoginFailureHistoryReturnsFalseWhenFailureCountAndLockAreEmpty() {
        LocalCredential localCredential = LocalCredential.create(
                UUID.randomUUID(),
                "hashed-password",
                lastChangedAt()
        );

        assertThat(localCredential.hasLoginFailureHistory()).isFalse();
    }

    private Instant lastChangedAt() {
        return Instant.parse("2025-01-01T00:00:00Z");
    }
}
