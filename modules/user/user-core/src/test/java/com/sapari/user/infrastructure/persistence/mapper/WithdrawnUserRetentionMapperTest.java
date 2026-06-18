package com.sapari.user.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.sapari.user.domain.model.WithdrawnUserRetention;
import com.sapari.user.infrastructure.persistence.entity.WithdrawnUserRetentionEntity;

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
                retentionUntil,
                now
        );

        // when
        WithdrawnUserRetentionEntity entity = mapper.toEntity(retention);

        // then
        assertThat(entity.getOriginalUserId()).isEqualTo(originalUserId);
        assertThat(entity.getNameMasked()).isEqualTo("홍*동");
        assertThat(entity.getEmailMasked()).isEqualTo("te***@example.com");
        assertThat(entity.getPhoneNumberMasked()).isEqualTo("010****5678");
        assertThat(entity.getRetentionUntil()).isEqualTo(retentionUntil);
        assertThat(entity.getCreatedAt()).isEqualTo(now);
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
                now,
                null
        );

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
}
