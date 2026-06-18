package com.sapari.user.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.sapari.user.model.ProviderType;
import com.sapari.user.domain.model.User;
import com.sapari.user.model.UserGender;
import com.sapari.user.model.UserRole;
import com.sapari.user.model.UserStatus;
import com.sapari.user.infrastructure.persistence.entity.UserEntity;

@DisplayName("User 영속성 매퍼 테스트")
class UserMapperTest {

    private final UserMapper mapper = Mappers.getMapper(UserMapper.class);

    @Test
    @DisplayName("UserEntity를 User 도메인 모델로 변환한다")
    void toDomainConvertsEntityToUser() {
        // given
        UserEntity entity = UserEntity.createSocialCustomer(
                "tester",
                "테스터",
                LocalDate.of(1995, 5, 15),
                UserGender.MALE,
                "01012345678",
                "tester@example.com",
                true,
                ProviderType.KAKAO,
                "provider-id",
                "provider@example.com",
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-01T00:00:00Z")
        );

        // when
        User user = mapper.toDomain(entity);

        // then
        assertThat(user.role()).isEqualTo(UserRole.USER);
        assertThat(user.nickname()).isEqualTo("tester");
        assertThat(user.gender()).isEqualTo(UserGender.MALE);
        assertThat(user.phoneNumber()).isEqualTo("01012345678");
        assertThat(user.provider()).isEqualTo(ProviderType.KAKAO);
        assertThat(user.nicknameChangedAt()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("User 도메인 모델을 신규 UserEntity로 변환한다")
    void toEntityConvertsUserToEntity() {
        // given
        User user = User.createSeller(
                "seller",
                "판매자",
                LocalDate.of(1990, 1, 1),
                "01087654321",
                "seller@example.com",
                false,
                Instant.parse("2025-01-01T00:00:00Z")
        );

        // when
        UserEntity entity = mapper.toEntity(user);

        // then
        assertThat(entity.getRole()).isEqualTo(UserRole.SELLER);
        assertThat(entity.getNickname()).isEqualTo("seller");
        assertThat(entity.getPhoneNumber()).isEqualTo("01087654321");
        assertThat(entity.getEmail()).isEqualTo("seller@example.com");
        assertThat(entity.getNicknameChangedAt()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("탈퇴 신청 상태와 유예 시작 시각을 UserEntity에 반영한다")
    void updateEntityFromDomainUpdatesWithdrawalState() {
        // given
        UserEntity entity = UserEntity.createSeller(
                "seller",
                "판매자",
                LocalDate.of(1990, 1, 1),
                "01087654321",
                "seller@example.com",
                false,
                Instant.parse("2025-01-01T00:00:00Z")
        );
        Instant deletedAt = Instant.parse("2026-06-13T12:00:00Z");
        User withdrawingUser = mapper.toDomain(entity)
                .requestWithdrawal(deletedAt);

        // when
        mapper.updateEntityFromDomain(entity, withdrawingUser);

        // then
        assertThat(entity.getStatus()).isEqualTo(UserStatus.WITHDRAWING);
        assertThat(entity.getDeletedAt()).isEqualTo(deletedAt);
    }
}
