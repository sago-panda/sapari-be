package com.sapari.user.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.sapari.user.domain.model.WithdrawnUserRetention;
import com.sapari.user.infrastructure.persistence.entity.WithdrawnUserRetentionEntity;
import com.sapari.storage.db.entity.BaseUuidEntity;

@DisplayName("WithdrawnUserRetention 영속성 매퍼 테스트")
class WithdrawnUserRetentionMapperTest {

    private final WithdrawnUserRetentionMapper mapper = Mappers.getMapper(WithdrawnUserRetentionMapper.class);

    @Test
    @DisplayName("WithdrawnUserRetention 도메인 모델을 신규 Entity로 변환한다")
    void toEntityConvertsDomainToEntity() {
        // given
        UUID originalUserId = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-15T09:40:00Z");
        Instant retentionUntil = Instant.parse("2031-06-15T09:40:00Z");
        WithdrawnUserRetention retention = WithdrawnUserRetention.create(
                originalUserId,
                "홍*동",
                "te***@example.com",
                "010****5678",
                retentionUntil
        );

        // when
        WithdrawnUserRetentionEntity entity = mapper.toEntity(retention);

        // then
        assertThat(entity.getOriginalUserId()).isEqualTo(originalUserId);
        assertThat(entity.getNameMasked()).isEqualTo("홍*동");
        assertThat(entity.getEmailMasked()).isEqualTo("te***@example.com");
        assertThat(entity.getPhoneNumberMasked()).isEqualTo("010****5678");
        assertThat(entity.getRetentionUntil()).isEqualTo(retentionUntil);
        assertThat(entity).isInstanceOf(BaseUuidEntity.class);
        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getPurgedAt()).isNull();
    }

    @Test
    @DisplayName("WithdrawnUserRetentionEntity를 도메인 모델로 변환한다")
    void toDomainConvertsEntityToDomain() {
        // given
        UUID originalUserId = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-15T09:40:00Z");
        Instant retentionUntil = Instant.parse("2031-06-15T09:40:00Z");
        WithdrawnUserRetentionEntity entity = WithdrawnUserRetentionEntity.of(
                originalUserId,
                "홍*동",
                "te***@example.com",
                "010****5678",
                retentionUntil,
                null
        );
        setCreatedAt(entity, now);

        // when
        WithdrawnUserRetention retention = mapper.toDomain(entity);

        // then
        assertThat(retention.originalUserId()).isEqualTo(originalUserId);
        assertThat(retention.nameMasked()).isEqualTo("홍*동");
        assertThat(retention.emailMasked()).isEqualTo("te***@example.com");
        assertThat(retention.phoneNumberMasked()).isEqualTo("010****5678");
        assertThat(retention.retentionUntil()).isEqualTo(retentionUntil);
        assertThat(retention.createdAt()).isEqualTo(now);
        assertThat(retention.purgedAt()).isNull();
    }

    private void setCreatedAt(WithdrawnUserRetentionEntity entity, Instant createdAt) {
        try {
            Field createdAtField = BaseUuidEntity.class.getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(entity, createdAt);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("createdAt 테스트 세팅에 실패했습니다.", e);
        }
    }
}
