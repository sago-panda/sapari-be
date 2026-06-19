package com.sapari.customer.application.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.sapari.customer.application.dto.SocialSignupInfo;
import com.sapari.customer.view.CustomerMeView;
import com.sapari.customer.view.CustomerNicknameUpdateResult;
import com.sapari.customer.view.SocialSignupInfoView;
import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserGender;
import com.sapari.user.model.UserGrade;
import com.sapari.user.model.UserRole;
import com.sapari.user.model.UserStatus;
import com.sapari.user.view.UserView;

class CustomerViewMapperTest {

    private final CustomerViewMapper mapper = Mappers.getMapper(CustomerViewMapper.class);

    @Test
    @DisplayName("UserView를 고객 내정보 View로 조립한다")
    void toMeView() {
        // given
        UserView customer = customerView();

        // when
        CustomerMeView view = mapper.toMeView(customer);

        // then
        assertThat(view.userId()).isEqualTo(customer.userId());
        assertThat(view.nickname()).isEqualTo(customer.nickname());
        assertThat(view.name()).isEqualTo(customer.name());
        assertThat(view.birthDate()).isEqualTo(customer.birthDate());
        assertThat(view.gender()).isEqualTo(customer.gender().name());
        assertThat(view.phoneNumber()).isEqualTo(customer.phoneNumber());
        assertThat(view.profileImageUrl()).isEqualTo(customer.profileImageUrl());
        assertThat(view.email()).isEqualTo(customer.email());
        assertThat(view.role()).isEqualTo(customer.role().name());
        assertThat(view.status()).isEqualTo(customer.status().name());
        assertThat(view.grade()).isEqualTo(customer.grade().name());
        assertThat(view.pointBalance()).isEqualTo(customer.pointBalance());
        assertThat(view.marketingAgreed()).isEqualTo(customer.marketingAgreed());
        assertThat(view.provider()).isEqualTo(customer.provider().name());
    }

    @Test
    @DisplayName("UserView와 Access Token을 닉네임 변경 결과로 조립한다")
    void toNicknameUpdateResult() {
        // given
        UserView customer = customerView();
        String accessToken = "access-token";

        // when
        CustomerNicknameUpdateResult result = mapper.toNicknameUpdateResult(customer, accessToken);

        // then
        assertThat(result.accessToken()).isEqualTo(accessToken);
        assertThat(result.customer().userId()).isEqualTo(customer.userId());
        assertThat(result.customer().nickname()).isEqualTo(customer.nickname());
    }

    @Test
    @DisplayName("소셜 가입 snapshot을 소셜 가입 정보 View로 조립한다")
    void toSocialSignupInfoView() {
        // given
        SocialSignupInfo socialSignupInfo = socialSignupInfo(UserGender.FEMALE);

        // when
        SocialSignupInfoView view = mapper.toSocialSignupInfoView(socialSignupInfo);

        // then
        assertThat(view.phoneNumber()).isEqualTo(socialSignupInfo.phoneNumber());
        assertThat(view.name()).isEqualTo(socialSignupInfo.name());
        assertThat(view.email()).isEqualTo(socialSignupInfo.providerEmail());
        assertThat(view.nickname()).isEqualTo(socialSignupInfo.nickname());
        assertThat(view.profileImageUrl()).isEqualTo(socialSignupInfo.profileImageUrl());
        assertThat(view.gender()).isEqualTo(UserGender.FEMALE.name());
        assertThat(view.birthDate()).isEqualTo(socialSignupInfo.birthDate());
    }

    @Test
    @DisplayName("소셜 가입 snapshot의 성별이 없으면 View 성별도 null로 조립한다")
    void toSocialSignupInfoViewWithNullGender() {
        // given
        SocialSignupInfo socialSignupInfo = socialSignupInfo(null);

        // when
        SocialSignupInfoView view = mapper.toSocialSignupInfoView(socialSignupInfo);

        // then
        assertThat(view.gender()).isNull();
    }

    private UserView customerView() {
        return new UserView(
                UUID.randomUUID(),
                UserRole.USER,
                UserStatus.ACTIVE,
                "customer",
                Instant.parse("2026-01-01T00:00:00Z"),
                "홍길동",
                LocalDate.of(1998, 3, 14),
                UserGender.MALE,
                "01012345678",
                "https://image.example/profile.png",
                "customer@example.com",
                UserGrade.BRONZE,
                100,
                true,
                ProviderType.KAKAO,
                "provider-id",
                "provider@example.com"
        );
    }

    private SocialSignupInfo socialSignupInfo(UserGender gender) {
        return new SocialSignupInfo(
                ProviderType.KAKAO,
                "provider-id",
                "provider@example.com",
                "홍길동",
                "customer",
                "01012345678",
                "https://example.com/profile.png",
                gender,
                LocalDate.of(1998, 3, 14)
        );
    }
}
