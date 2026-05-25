package com.sapari.user.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("User 도메인 모델 테스트")
class UserTest {

    @Test
    @DisplayName("소셜 회원 생성 시 구매자 기본 상태를 설정한다")
    void createSocialMemberSetsMemberDefaults() {
        // given
        LocalDate birthDate = LocalDate.of(1995, 5, 15);

        // when
        User user = User.createSocialMember(
                "tester",
                "테스터",
                birthDate,
                "01012345678",
                "tester@example.com",
                true,
                ProviderType.KAKAO,
                "provider-id",
                "provider@example.com"
        );

        // then
        assertThat(user.role()).isEqualTo(UserRole.USER);
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.grade()).isEqualTo(UserGrade.BRONZE);
        assertThat(user.pointBalance()).isZero();
        assertThat(user.marketingAgreed()).isTrue();
        assertThat(user.provider()).isEqualTo(ProviderType.KAKAO);
        assertThat(user.providerCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("판매자 생성 시 판매자 기본 상태를 설정한다")
    void createSellerSetsSellerDefaults() {
        // given
        LocalDate birthDate = LocalDate.of(1990, 1, 1);

        // when
        User user = User.createSeller(
                "seller",
                "판매자",
                birthDate,
                "01087654321",
                "seller@example.com",
                null
        );

        // then
        assertThat(user.role()).isEqualTo(UserRole.SELLER);
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.grade()).isEqualTo(UserGrade.BRONZE);
        assertThat(user.pointBalance()).isZero();
        assertThat(user.marketingAgreed()).isFalse();
        assertThat(user.isSeller()).isTrue();
    }

    @Test
    @DisplayName("프로필 수정 시 수정된 User를 반환한다")
    void updateProfileReturnsUpdatedUser() {
        // given
        User user = User.createSocialMember(
                "tester",
                "테스터",
                LocalDate.of(1995, 5, 15),
                "01012345678",
                "tester@example.com",
                false,
                ProviderType.NAVER,
                "provider-id",
                "provider@example.com"
        );
        LocalDate changedBirthDate = LocalDate.of(1996, 6, 16);

        // when
        User updatedUser = user.updateProfile(
                "updated",
                "수정자",
                changedBirthDate,
                "01011112222",
                "profile/image/key",
                "updated@example.com",
                true
        );

        // then
        assertThat(updatedUser.nickname()).isEqualTo("updated");
        assertThat(updatedUser.name()).isEqualTo("수정자");
        assertThat(updatedUser.birthDate()).isEqualTo(changedBirthDate);
        assertThat(updatedUser.phoneNumber()).isEqualTo("01011112222");
        assertThat(updatedUser.profileImageKey()).isEqualTo("profile/image/key");
        assertThat(updatedUser.email()).isEqualTo("updated@example.com");
        assertThat(updatedUser.marketingAgreed()).isTrue();
    }

    @Test
    @DisplayName("필수값이 비어 있으면 예외가 발생한다")
    void createSocialMemberThrowsExceptionWhenRequiredValueIsBlank() {
        // given
        String blankNickname = " ";

        // when, then
        assertThatThrownBy(() -> User.createSocialMember(
                blankNickname,
                "테스터",
                LocalDate.of(1995, 5, 15),
                "01012345678",
                "tester@example.com",
                false,
                ProviderType.KAKAO,
                "provider-id",
                "provider@example.com"
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
