package com.sapari.user.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.sapari.storage.db.entity.BaseUuidEntity;
import com.sapari.user.domain.model.UserTermsAgreement;
import com.sapari.user.infrastructure.persistence.entity.UserTermsAgreementEntity;

@DisplayName("UserTermsAgreement 영속성 매퍼 테스트")
class UserTermsAgreementMapperTest {

    private final UserTermsAgreementMapper mapper = Mappers.getMapper(UserTermsAgreementMapper.class);

    @Test
    @DisplayName("UserTermsAgreementEntity의 id를 도메인 userTermsAgreementId로 매핑한다")
    void toDomainMapsEntityIdToUserTermsAgreementId() throws Exception {
        // given
        UUID userTermsAgreementId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID termsId = UUID.randomUUID();
        Instant agreedAt = Instant.parse("2026-06-21T01:00:00Z");
        UserTermsAgreementEntity entity = UserTermsAgreementEntity.of(userId, termsId, true, agreedAt);
        setId(entity, userTermsAgreementId);

        // when
        UserTermsAgreement agreement = mapper.toDomain(entity);

        // then
        assertThat(agreement.userTermsAgreementId()).isEqualTo(userTermsAgreementId);
        assertThat(agreement.userId()).isEqualTo(userId);
        assertThat(agreement.termsId()).isEqualTo(termsId);
        assertThat(agreement.agreedAt()).isEqualTo(agreedAt);
    }

    @Test
    @DisplayName("약관 증적 저장에 필요한 값을 Entity로 매핑한다")
    void toEntityMapsRequiredAgreementEvidenceFields() {
        // given
        UUID userId = UUID.randomUUID();
        UUID termsId = UUID.randomUUID();
        Instant agreedAt = Instant.parse("2026-06-21T01:00:00Z");
        UserTermsAgreement agreement = UserTermsAgreement.create(userId, termsId, false, agreedAt);

        // when
        UserTermsAgreementEntity entity = mapper.toEntity(agreement);

        // then
        assertThat(entity.getUserId()).isEqualTo(userId);
        assertThat(entity.getTermsId()).isEqualTo(termsId);
        assertThat(entity.isAgreed()).isFalse();
        assertThat(entity.getAgreedAt()).isEqualTo(agreedAt);
    }

    private void setId(UserTermsAgreementEntity entity, UUID id) throws Exception {
        Field idField = BaseUuidEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(entity, id);
    }
}
