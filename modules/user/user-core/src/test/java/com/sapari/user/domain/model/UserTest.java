package com.sapari.user.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserGender;
import com.sapari.user.model.UserGrade;
import com.sapari.user.model.UserRole;
import com.sapari.user.model.UserStatus;

@DisplayName("User 도메인 모델 테스트")
class UserTest {

    @Test
    @DisplayName("소셜 회원 생성 시 구매자 기본 상태를 설정한다")
    void createSocialMemberSetsMemberDefaults() {
        // given
        LocalDate birthDate = LocalDate.of(1995, 5, 15);
        Instant providerCreatedAt = providerCreatedAt();
        Instant nicknameChangedAt = nicknameChangedAt();

        // when
        User user = User.createSocialMember(
                "tester",
                "테스터",
                birthDate,
                UserGender.FEMALE,
                "01012345678",
                "tester@example.com",
                true,
                ProviderType.KAKAO,
                "provider-id",
                "provider@example.com",
                providerCreatedAt,
                nicknameChangedAt
        );

        // then
        assertThat(user.role()).isEqualTo(UserRole.USER);
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.grade()).isEqualTo(UserGrade.BRONZE);
        assertThat(user.pointBalance()).isZero();
        assertThat(user.marketingAgreed()).isTrue();
        assertThat(user.gender()).isEqualTo(UserGender.FEMALE);
        assertThat(user.provider()).isEqualTo(ProviderType.KAKAO);
        assertThat(user.providerCreatedAt()).isEqualTo(providerCreatedAt);
        assertThat(user.nicknameChangedAt()).isEqualTo(nicknameChangedAt);
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
                null,
                nicknameChangedAt()
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
    @DisplayName("닉네임 수정 시 닉네임만 변경한 User를 반환한다")
    void updateNicknameReturnsUpdatedUser() {
        // given
        User user = User.createSocialMember(
                "tester",
                "테스터",
                LocalDate.of(1995, 5, 15),
                UserGender.MALE,
                "01012345678",
                "tester@example.com",
                false,
                ProviderType.NAVER,
                "provider-id",
                "provider@example.com",
                providerCreatedAt(),
                nicknameChangedAt()
        );
        Instant changedAt = Instant.parse("2025-02-01T00:00:00Z");

        // when
        User updatedUser = user.updateNickname("updated", changedAt);

        // then
        assertThat(updatedUser.nickname()).isEqualTo("updated");
        assertThat(updatedUser.name()).isEqualTo("테스터");
        assertThat(updatedUser.birthDate()).isEqualTo(LocalDate.of(1995, 5, 15));
        assertThat(updatedUser.phoneNumber()).isEqualTo("01012345678");
        assertThat(updatedUser.email()).isEqualTo("tester@example.com");
        assertThat(updatedUser.marketingAgreed()).isFalse();
        assertThat(updatedUser.nicknameChangedAt()).isEqualTo(changedAt);
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
                UserGender.MALE,
                "01012345678",
                "tester@example.com",
                false,
                ProviderType.KAKAO,
                "provider-id",
                "provider@example.com",
                providerCreatedAt(),
                nicknameChangedAt()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private Instant providerCreatedAt() {
        return Instant.parse("2025-01-01T00:00:00Z");
    }

    private Instant nicknameChangedAt() {
        return Instant.parse("2025-01-01T00:00:00Z");
    }
}
