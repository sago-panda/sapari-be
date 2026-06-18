package com.sapari.user.infrastructure.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserGender;
import com.sapari.user.model.UserGrade;
import com.sapari.user.model.UserRole;
import com.sapari.user.model.UserStatus;

class UserEntityTest {

    @Test
    @DisplayName("소셜 고객 생성 시 고객 기본 상태와 소셜 제공자 정보를 설정한다")
    void createSocialCustomer() {
        // given
        LocalDate birthDate = LocalDate.of(1995, 5, 15);
        Instant providerCreatedAt = providerCreatedAt();
        Instant nicknameChangedAt = nicknameChangedAt();

        // when
        UserEntity user = UserEntity.createSocialCustomer(
                "tester",
                "테스터",
                birthDate,
                UserGender.FEMALE,
                "01012345678",
                "tester@example.com",
                "https://image.example/profile.png",
                true,
                ProviderType.KAKAO,
                "provider-id",
                "provider@example.com",
                providerCreatedAt,
                nicknameChangedAt
        );

        // then
        assertThat(user.getRole()).isEqualTo(UserRole.USER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getNickname()).isEqualTo("tester");
        assertThat(user.getName()).isEqualTo("테스터");
        assertThat(user.getBirthDate()).isEqualTo(birthDate);
        assertThat(user.getGender()).isEqualTo(UserGender.FEMALE);
        assertThat(user.getPhoneNumber()).isEqualTo("01012345678");
        assertThat(user.getEmail()).isEqualTo("tester@example.com");
        assertThat(user.getGrade()).isEqualTo(UserGrade.BRONZE);
        assertThat(user.getPointBalance()).isZero();
        assertThat(user.getMarketingAgreed()).isTrue();
        assertThat(user.getProvider()).isEqualTo(ProviderType.KAKAO);
        assertThat(user.getProviderId()).isEqualTo("provider-id");
        assertThat(user.getProviderEmail()).isEqualTo("provider@example.com");
        assertThat(user.getProviderCreatedAt()).isEqualTo(providerCreatedAt);
        assertThat(user.getNicknameChangedAt()).isEqualTo(nicknameChangedAt);
    }

    @Test
    @DisplayName("판매자 생성 시 판매자 기본 상태를 설정한다")
    void createSeller() {
        // when
        UserEntity user = UserEntity.createSeller(
                "seller",
                "판매자",
                "01087654321",
                "seller@example.com",
                null,
                nicknameChangedAt()
        );

        // then
        assertThat(user.getRole()).isEqualTo(UserRole.SELLER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getNickname()).isEqualTo("seller");
        assertThat(user.getName()).isEqualTo("판매자");
        assertThat(user.getBirthDate()).isNull();
        assertThat(user.getPhoneNumber()).isEqualTo("01087654321");
        assertThat(user.getEmail()).isEqualTo("seller@example.com");
        assertThat(user.getGrade()).isEqualTo(UserGrade.BRONZE);
        assertThat(user.getPointBalance()).isZero();
        assertThat(user.getMarketingAgreed()).isFalse();
    }

    @Test
    @DisplayName("프로필 수정 시 변경 가능한 프로필 필드를 갱신한다")
    void updateProfile() {
        // given
        UserEntity user = UserEntity.createSocialCustomer(
                "tester",
                "테스터",
                LocalDate.of(1995, 5, 15),
                UserGender.MALE,
                "01012345678",
                "tester@example.com",
                "https://image.example/profile.png",
                false,
                ProviderType.NAVER,
                "provider-id",
                "provider@example.com",
                providerCreatedAt(),
                nicknameChangedAt()
        );
        LocalDate changedBirthDate = LocalDate.of(1996, 6, 16);
        Instant changedAt = Instant.parse("2025-02-01T00:00:00Z");

        // when
        user.updateProfile(
                "updated",
                "수정자",
                changedBirthDate,
                "01011112222",
                "profile/image/key",
                "updated@example.com",
                true,
                changedAt
        );

        // then
        assertThat(user.getNickname()).isEqualTo("updated");
        assertThat(user.getName()).isEqualTo("수정자");
        assertThat(user.getBirthDate()).isEqualTo(changedBirthDate);
        assertThat(user.getPhoneNumber()).isEqualTo("01011112222");
        assertThat(user.getProfileImageKey()).isEqualTo("profile/image/key");
        assertThat(user.getEmail()).isEqualTo("updated@example.com");
        assertThat(user.getMarketingAgreed()).isTrue();
        assertThat(user.getNicknameChangedAt()).isEqualTo(changedAt);
    }

    private Instant providerCreatedAt() {
        return Instant.parse("2025-01-01T00:00:00Z");
    }

    private Instant nicknameChangedAt() {
        return Instant.parse("2025-01-01T00:00:00Z");
    }
}
