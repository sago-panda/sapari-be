package com.sapari.productapp.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * 문자열이 JSON 객체(예: {@code {"hex":"#FF0000"}})로 파싱 가능한지 검증한다. {@code null}은 허용한다(선택 필드).
 *
 * <p>jsonb 컬럼으로 저장되는 metadata가 비-JSON 문자열일 때 영속 시점의 {@code DataIntegrityViolation}(500)
 * 대신 입력 검증 단계에서 400으로 거르기 위한 최소 검증이다. 키 화이트리스트·깊은 구조 검증(m-2)은 후속.
 */
@Documented
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
@Constraint(validatedBy = JsonObjectStringValidator.class)
public @interface JsonObjectString {

    String message() default "JSON 객체 형식이어야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
