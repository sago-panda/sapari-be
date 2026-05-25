package com.sapari.seller.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.seller.domain.model.LocalCredential;
import com.sapari.seller.infrastructure.persistence.entity.LocalCredentialEntity;

@DisplayName("로컬 인증 정보 매퍼 테스트")
class LocalCredentialMapperTest {

    @Test
    @DisplayName("도메인 모델을 JPA 엔티티로 변환한다")
    void toEntityMapsDomainToEntity() {
        // given
        UUID userId = UUID.randomUUID();
        LocalDateTime lockedAt = LocalDateTime.of(2026, 1, 1, 10, 0);
        LocalDateTime lastChangedAt = LocalDateTime.of(2026, 1, 2, 10, 0);
        LocalCredential localCredential = new LocalCredential(
                userId,
                "hashed-password",
                2,
                lockedAt,
                lastChangedAt
        );

        // when
        LocalCredentialEntity entity = LocalCredentialMapper.toEntity(localCredential);

        // then
        assertThat(entity.getUserId()).isEqualTo(userId);
        assertThat(entity.getPasswordHash()).isEqualTo("hashed-password");
        assertThat(entity.getFailedLoginCount()).isEqualTo(2);
        assertThat(entity.getLockedAt()).isEqualTo(lockedAt);
        assertThat(entity.getLastChangedAt()).isEqualTo(lastChangedAt);
    }

    @Test
    @DisplayName("JPA 엔티티를 도메인 모델로 변환한다")
    void toDomainMapsEntityToDomain() {
        // given
        UUID userId = UUID.randomUUID();
        LocalDateTime lastChangedAt = LocalDateTime.of(2026, 1, 2, 10, 0);
        LocalCredentialEntity entity = LocalCredentialEntity.of(
                userId,
                "hashed-password",
                0,
                null,
                lastChangedAt
        );

        // when
        LocalCredential localCredential = LocalCredentialMapper.toDomain(entity);

        // then
        assertThat(localCredential.userId()).isEqualTo(userId);
        assertThat(localCredential.passwordHash()).isEqualTo("hashed-password");
        assertThat(localCredential.failedLoginCount()).isZero();
        assertThat(localCredential.lockedAt()).isNull();
        assertThat(localCredential.lastChangedAt()).isEqualTo(lastChangedAt);
    }
}
