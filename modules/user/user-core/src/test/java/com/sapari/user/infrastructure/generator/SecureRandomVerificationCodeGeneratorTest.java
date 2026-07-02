package com.sapari.user.infrastructure.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SecureRandom 인증번호 생성기 테스트")
class SecureRandomVerificationCodeGeneratorTest {

    @Test
    @DisplayName("요청한 길이의 숫자 인증번호를 생성한다")
    void generateNumericCodeReturnsRequestedLength() {
        SecureRandomVerificationCodeGenerator generator = new SecureRandomVerificationCodeGenerator();

        String code = generator.generateNumericCode(6);

        assertThat(code).hasSize(6);
        assertThat(code).containsOnlyDigits();
    }

    @Test
    @DisplayName("난수 값이 짧아도 앞자리를 0으로 채워 고정 길이를 유지한다")
    void generateNumericCodeKeepsLeadingZeroPadding() {
        SecureRandomVerificationCodeGenerator generator =
                new SecureRandomVerificationCodeGenerator(new FixedSecureRandom(42));

        String code = generator.generateNumericCode(6);

        assertThat(code).isEqualTo("000042");
    }

    @Test
    @DisplayName("0 이하 길이는 인증번호 정책 오류로 거부한다")
    void generateNumericCodeRejectsNonPositiveLength() {
        SecureRandomVerificationCodeGenerator generator = new SecureRandomVerificationCodeGenerator();

        assertThatThrownBy(() -> generator.generateNumericCode(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code length");
    }

    @Test
    @DisplayName("9자리 숫자 인증번호까지 생성할 수 있다")
    void generateNumericCodeAllowsLengthNine() {
        SecureRandomVerificationCodeGenerator generator =
                new SecureRandomVerificationCodeGenerator(new FixedSecureRandom(123456789));

        String code = generator.generateNumericCode(9);

        assertThat(code).isEqualTo("123456789");
    }

    @Test
    @DisplayName("10자리 이상 길이는 int bound 오버플로 위험 때문에 거부한다")
    void generateNumericCodeRejectsLengthOverNine() {
        SecureRandomVerificationCodeGenerator generator = new SecureRandomVerificationCodeGenerator();

        assertThatThrownBy(() -> generator.generateNumericCode(10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 9");
    }

    private static final class FixedSecureRandom extends SecureRandom {

        private final int fixedValue;

        private FixedSecureRandom(int fixedValue) {
            this.fixedValue = fixedValue;
        }

        @Override
        public int nextInt(int bound) {
            return fixedValue;
        }
    }
}
