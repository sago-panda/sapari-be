package com.sapari.apiapp.controller.customer.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.customer.command.SocialSignupCommand;

@DisplayName("SocialSignupRequest 테스트")
class SocialSignupRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("marketingAgreed가 null이면 false로 정규화한다")
    void toCommandNormalizesNullMarketingAgreedToFalse() {
        // given
        SocialSignupRequest request = request(true, null);

        // when
        SocialSignupCommand command = request.toCommand();

        // then
        assertThat(command.privacyAgreed()).isTrue();
        assertThat(command.marketingAgreed()).isFalse();
    }

    @Test
    @DisplayName("privacyAgreed가 null이면 false로 정규화한다")
    void toCommandNormalizesNullPrivacyAgreedToFalse() {
        // given
        SocialSignupRequest request = request(null, true);

        // when
        SocialSignupCommand command = request.toCommand();

        // then
        assertThat(command.privacyAgreed()).isFalse();
        assertThat(command.marketingAgreed()).isTrue();
    }

    @Test
    @DisplayName("privacyAgreed가 false이면 요청 검증에 실패한다")
    void validationRejectsPrivacyAgreedFalse() {
        // given
        SocialSignupRequest request = request(false, true);

        // when, then
        assertThat(validator.validate(request))
                .anySatisfy(violation -> assertThat(violation.getMessage()).isEqualTo("개인정보 수집 및 이용 동의는 필수입니다."));
    }

    @Test
    @DisplayName("privacyAgreed가 null이면 요청 검증에 실패한다")
    void validationRejectsPrivacyAgreedNull() {
        // given
        SocialSignupRequest request = request(null, true);

        // when, then
        assertThat(validator.validate(request))
                .anySatisfy(violation -> assertThat(violation.getMessage()).isEqualTo("개인정보 수집 및 이용 동의는 필수입니다."));
    }

    private SocialSignupRequest request(Boolean privacyAgreed, Boolean marketingAgreed) {
        return new SocialSignupRequest(
                "01012345678",
                "customer@example.com",
                "customer",
                "구매자",
                LocalDate.of(2000, 1, 1),
                "FEMALE",
                "https://image.example/profile.png",
                privacyAgreed,
                marketingAgreed
        );
    }
}
