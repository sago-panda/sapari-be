package com.sapari.productapp.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link JsonObjectStringValidator} 단위 테스트. metadata 문자열이 JSON 객체로 파싱되는지(→ 통과)와
 * 비-객체·파싱 불가(→ 거부, 영속 전 400 유도)를 고정한다.
 */
class JsonObjectStringValidatorTest {

    private final JsonObjectStringValidator validator = new JsonObjectStringValidator();

    /**
     * null은 선택 필드이므로 유효하다.
     */
    @Test
    void null은_유효하다() {
        assertThat(validator.isValid(null, null)).isTrue();
    }

    /**
     * JSON 객체 문자열은 유효하다.
     *
     * @param value 객체로 파싱되는 JSON 문자열
     */
    @ParameterizedTest
    @ValueSource(strings = {"{\"hex\":\"#FF0000\"}", "{}", "{\"a\":{\"b\":1}}"})
    void JSON_객체는_유효하다(String value) {
        assertThat(validator.isValid(value, null)).isTrue();
    }

    /**
     * 객체가 아니거나 파싱 불가한 문자열은 무효다(배열·스칼라·깨진 JSON·빈 문자열).
     *
     * @param value 객체가 아닌 입력
     */
    @ParameterizedTest
    @ValueSource(strings = {"[1,2]", "\"foo\"", "123", "true", "{\"a\":}", "not-json", ""})
    void 비객체나_파싱불가는_무효다(String value) {
        assertThat(validator.isValid(value, null)).isFalse();
    }
}
