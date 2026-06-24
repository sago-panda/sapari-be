package com.sapari.customer.infrastructure.hash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HMAC 인증 해시 생성기 테스트")
class HmacVerificationCodeHasherTest {

    @Test
    @DisplayName("같은 전화번호는 같은 phoneHash를 반환한다")
    void hashPhoneNumberReturnsSameHashForSamePhone() {
        HmacVerificationCodeHasher hasher = new HmacVerificationCodeHasher("test-hmac-secret");

        String first = hasher.hashPhoneNumber("010-1234-5678");
        String second = hasher.hashPhoneNumber("01012345678");

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("전화번호가 다르면 다른 phoneHash를 반환한다")
    void hashPhoneNumberReturnsDifferentHashForDifferentPhone() {
        HmacVerificationCodeHasher hasher = new HmacVerificationCodeHasher("test-hmac-secret");

        String first = hasher.hashPhoneNumber("01012345678");
        String second = hasher.hashPhoneNumber("01087654321");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("같은 전화번호라도 인증번호가 다르면 다른 codeHash를 반환한다")
    void hashCodeReturnsDifferentHashForDifferentCode() {
        HmacVerificationCodeHasher hasher = new HmacVerificationCodeHasher("test-hmac-secret");

        String first = hasher.hashCode("01012345678", "123456");
        String second = hasher.hashCode("01012345678", "654321");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("하이픈과 공백이 있어도 같은 phoneHash를 반환한다")
    void hashPhoneNumberNormalizesHyphenAndWhitespace() {
        HmacVerificationCodeHasher hasher = new HmacVerificationCodeHasher("test-hmac-secret");

        String first = hasher.hashPhoneNumber("010-1234 5678");
        String second = hasher.hashPhoneNumber("01012345678");

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("HMAC secret이 비어 있으면 설정 오류로 실패한다")
    void constructorRejectsBlankSecret() {
        assertThatThrownBy(() -> new HmacVerificationCodeHasher("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hmacSecret");
    }
}
