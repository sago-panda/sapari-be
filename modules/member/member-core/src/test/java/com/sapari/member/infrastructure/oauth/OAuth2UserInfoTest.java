package com.sapari.member.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserGender;

@DisplayName("OAuth2 사용자 정보 파싱 테스트")
class OAuth2UserInfoTest {

    @Test
    @DisplayName("Naver profile에서 가입 기본 정보를 파싱한다")
    void naverProfileParsesSocialSignupInfo() {
        // given
        NaverOAuth2UserInfo userInfo = new NaverOAuth2UserInfo(Map.of(
                "response", Map.of(
                        "id", "naver-id",
                        "email", "member@naver.com",
                        "name", "홍길동",
                        "nickname", "길동",
                        "mobile", "+82 10-1234-5678",
                        "profile_image", "https://image.example/naver.png",
                        "gender", "M",
                        "birthyear", "1998",
                        "birthday", "03-14"
                )
        ));

        // when, then
        assertThat(userInfo.provider()).isEqualTo(ProviderType.NAVER);
        assertThat(userInfo.providerId()).isEqualTo("naver-id");
        assertThat(userInfo.providerEmail()).isEqualTo("member@naver.com");
        assertThat(userInfo.name()).isEqualTo("홍길동");
        assertThat(userInfo.nickname()).isEqualTo("길동");
        assertThat(userInfo.phoneNumber()).isEqualTo("01012345678");
        assertThat(userInfo.profileImageUrl()).isEqualTo("https://image.example/naver.png");
        assertThat(userInfo.gender()).isEqualTo(UserGender.MALE);
        assertThat(userInfo.birthDate()).isEqualTo(LocalDate.of(1998, 3, 14));
    }

    @Test
    @DisplayName("Kakao profile에서 가입 기본 정보를 파싱한다")
    void kakaoProfileParsesSocialSignupInfo() {
        // given
        KakaoOAuth2UserInfo userInfo = new KakaoOAuth2UserInfo(Map.of(
                "id", 12345L,
                "kakao_account", Map.of(
                        "email", "member@kakao.com",
                        "name", "홍길동",
                        "phone_number", "+82 10-2222-3333",
                        "gender", "female",
                        "birthyear", "1997",
                        "birthday", "0405",
                        "profile", Map.of(
                                "nickname", "길동",
                                "profile_image_url", "https://image.example/kakao.png"
                        )
                )
        ));

        // when, then
        assertThat(userInfo.provider()).isEqualTo(ProviderType.KAKAO);
        assertThat(userInfo.providerId()).isEqualTo("12345");
        assertThat(userInfo.providerEmail()).isEqualTo("member@kakao.com");
        assertThat(userInfo.name()).isEqualTo("홍길동");
        assertThat(userInfo.nickname()).isEqualTo("길동");
        assertThat(userInfo.phoneNumber()).isEqualTo("01022223333");
        assertThat(userInfo.profileImageUrl()).isEqualTo("https://image.example/kakao.png");
        assertThat(userInfo.gender()).isEqualTo(UserGender.FEMALE);
        assertThat(userInfo.birthDate()).isEqualTo(LocalDate.of(1997, 4, 5));
    }

    @Test
    @DisplayName("birthyear 또는 birthday가 없으면 생년월일을 만들지 않는다")
    void missingBirthPartReturnsNullBirthDate() {
        // given
        NaverOAuth2UserInfo userInfo = new NaverOAuth2UserInfo(Map.of(
                "response", Map.of(
                        "id", "naver-id",
                        "birthday", "03-14"
                )
        ));

        // when, then
        assertThat(userInfo.birthDate()).isNull();
    }

    @Test
    @DisplayName("provider 전화번호는 숫자만 남긴다")
    void providerPhoneNumberKeepsOnlyDigits() {
        // given
        NaverOAuth2UserInfo userInfo = new NaverOAuth2UserInfo(Map.of(
                "response", Map.of(
                        "id", "naver-id",
                        "mobile", "010-9876-5432"
                )
        ));

        // when, then
        assertThat(userInfo.phoneNumber()).isEqualTo("01098765432");
    }
}
