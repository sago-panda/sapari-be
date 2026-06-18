package com.sapari.user.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("탈퇴회원 보존 식별정보 마스킹 테스트")
class WithdrawnUserRetentionMaskerTest {

    private final WithdrawnUserRetentionMasker masker = new WithdrawnUserRetentionMasker();

    @Test
    @DisplayName("이름은 첫 글자와 마지막 글자만 남기고 가운데를 마스킹한다")
    void maskNameKeepsOnlyFirstAndLastCharacter() {
        assertThat(masker.maskName("홍길동")).isEqualTo("홍*동");
        assertThat(masker.maskName("김철수")).isEqualTo("김*수");
        assertThat(masker.maskName("남궁민수")).isEqualTo("남**수");
    }

    @Test
    @DisplayName("짧은 이름은 원문이 드러나지 않도록 마스킹한다")
    void maskNameHandlesShortName() {
        assertThat(masker.maskName("홍길")).isEqualTo("홍*");
        assertThat(masker.maskName("홍")).isEqualTo("*");
    }

    @Test
    @DisplayName("이메일은 local-part 앞 일부와 domain만 남긴다")
    void maskEmailKeepsLocalPrefixAndDomain() {
        assertThat(masker.maskEmail("test@example.com")).isEqualTo("te***@example.com");
        assertThat(masker.maskEmail("ab@example.com")).isEqualTo("ab***@example.com");
        assertThat(masker.maskEmail("a@example.com")).isEqualTo("a***@example.com");
    }

    @Test
    @DisplayName("전화번호는 앞 3자리와 뒤 4자리만 남긴다")
    void maskPhoneNumberKeepsPrefixAndSuffix() {
        assertThat(masker.maskPhoneNumber("01012345678")).isEqualTo("010****5678");
        assertThat(masker.maskPhoneNumber("01198765432")).isEqualTo("011****5432");
    }

    @Test
    @DisplayName("null 또는 blank 값은 null로 반환한다")
    void maskReturnsNullForNullOrBlank() {
        assertThat(masker.maskName(null)).isNull();
        assertThat(masker.maskName("   ")).isNull();
        assertThat(masker.maskEmail(null)).isNull();
        assertThat(masker.maskEmail("   ")).isNull();
        assertThat(masker.maskPhoneNumber(null)).isNull();
        assertThat(masker.maskPhoneNumber("   ")).isNull();
    }
}
