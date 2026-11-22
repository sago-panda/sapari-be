package com.sapari.user.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.sapari.storage.db.entity.BaseUuidEntity;
import com.sapari.user.domain.model.Terms;
import com.sapari.user.infrastructure.persistence.entity.TermsEntity;
import com.sapari.user.model.TermsType;

@DisplayName("Terms 영속성 매퍼 테스트")
class TermsMapperTest {

    private final TermsMapper mapper = Mappers.getMapper(TermsMapper.class);

    @Test
    @DisplayName("TermsEntity의 id를 도메인 termsId로 매핑한다")
    void toDomainMapsEntityIdToTermsId() throws Exception {
        // given
        UUID termsId = UUID.randomUUID();
        Instant effectiveFrom = Instant.parse("2026-06-21T00:00:00Z");
        TermsEntity entity = TermsEntity.of(
                TermsType.MARKETING,
                "v1.0",
                "마케팅 정보 수신 동의",
                false,
                "/test/terms/marketing/v1.0",
                "MARKDOWN",
                effectiveFrom,
                true
        );
        setId(entity, termsId);

        // when
        Terms terms = mapper.toDomain(entity);

        // then
        assertThat(terms.termsId()).isEqualTo(termsId);
        assertThat(terms.type()).isEqualTo(TermsType.MARKETING);
        assertThat(terms.effectiveFrom()).isEqualTo(effectiveFrom);
    }

    @Test
    @DisplayName("Terms를 저장할 때 필요한 약관 메타데이터를 Entity로 매핑한다")
    void toEntityMapsRequiredTermsMetadata() {
        // given
        Instant effectiveFrom = Instant.parse("2026-06-21T00:00:00Z");
        Terms terms = Terms.create(
                TermsType.PRIVACY,
                "v1.0",
                "개인정보 수집 및 이용 동의",
                true,
                "/test/terms/privacy/v1.0",
                "MARKDOWN",
                effectiveFrom
        );

        // when
        TermsEntity entity = mapper.toEntity(terms);

        // then
        assertThat(entity.getType()).isEqualTo(TermsType.PRIVACY);
        assertThat(entity.getVersion()).isEqualTo("v1.0");
        assertThat(entity.isRequired()).isTrue();
        assertThat(entity.getContentUrl()).isEqualTo("/test/terms/privacy/v1.0");
        assertThat(entity.getEffectiveFrom()).isEqualTo(effectiveFrom);
    }

    private void setId(TermsEntity entity, UUID id) throws Exception {
        Field idField = BaseUuidEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(entity, id);
    }
}
