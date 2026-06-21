package com.sapari.apiapp.controller.seller.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.seller.command.SellerSignupCommand;

@DisplayName("SellerSignupRequest 테스트")
class SellerSignupRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("marketingAgreed가 null이면 false로 정규화한다")
    void toCommandNormalizesNullMarketingAgreedToFalse() {
        // given
        SellerSignupRequest request = request(true, null);

        // when
        SellerSignupCommand command = request.toCommand();

        // then
        assertThat(command.privacyAgreed()).isTrue();
        assertThat(command.marketingAgreed()).isFalse();
    }

    @Test
    @DisplayName("privacyAgreed가 null이면 false로 정규화한다")
    void toCommandNormalizesNullPrivacyAgreedToFalse() {
        // given
        SellerSignupRequest request = request(null, true);

        // when
        SellerSignupCommand command = request.toCommand();

        // then
        assertThat(command.privacyAgreed()).isFalse();
        assertThat(command.marketingAgreed()).isTrue();
    }

    @Test
    @DisplayName("privacyAgreed가 false이면 요청 검증에 실패한다")
    void validationRejectsPrivacyAgreedFalse() {
        // given
        SellerSignupRequest request = request(false, true);

        // when, then
        assertThat(validator.validate(request))
                .anySatisfy(violation -> assertThat(violation.getMessage()).isEqualTo("개인정보 수집 및 이용 동의는 필수입니다."));
    }

    @Test
    @DisplayName("privacyAgreed가 null이면 요청 검증에 실패한다")
    void validationRejectsPrivacyAgreedNull() {
        // given
        SellerSignupRequest request = request(null, true);

        // when, then
        assertThat(validator.validate(request))
                .anySatisfy(violation -> assertThat(violation.getMessage()).isEqualTo("개인정보 수집 및 이용 동의는 필수입니다."));
    }

    private SellerSignupRequest request(Boolean privacyAgreed, Boolean marketingAgreed) {
        return new SellerSignupRequest(
                "seller@example.com",
                "Password1!",
                "Password1!",
                "seller",
                "판매자",
                "01012345678",
                privacyAgreed,
                marketingAgreed,
                "사파리 상점",
                "1234567890",
                LocalDate.of(2020, 1, 1),
                "INDIVIDUAL"
        );
    }
}
