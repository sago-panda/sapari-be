package com.sapari.productapp.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link JsonObjectString}의 검증기. 문자열을 JSON으로 파싱해 객체 노드인지 확인한다.
 */
public class JsonObjectStringValidator implements ConstraintValidator<JsonObjectString, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 값이 JSON 객체로 파싱되면 유효로 판정한다. {@code null}은 선택 필드이므로 유효, 그 외 파싱 실패·비객체는 무효다.
     *
     * @param value   검증 대상 문자열(jsonb로 저장될 metadata)
     * @param context 제약 컨텍스트
     * @return 유효 여부
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(value);
            return node != null && node.isObject();
        } catch (JacksonException e) {
            return false;
        }
    }
}
