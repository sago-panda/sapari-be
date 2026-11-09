package com.sapari.liveapp.controller.live.dto.constraint;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Size;

/**
 * 방송 설명 길이 한도(≤{@value LiveFieldLimits#DESCRIPTION_MAX}). 설명은 선택 항목이라 null 은 통과한다 —
 * 방 생성과 방 수정(SPR-158)이 같은 애너테이션을 쓴다. 공백뿐인 설명은 도메인이 없음(null)으로 저장한다.
 */
@Documented
@Constraint(validatedBy = {})
@Size(max = LiveFieldLimits.DESCRIPTION_MAX)
@ReportAsSingleViolation
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE, TYPE_USE})
@Retention(RUNTIME)
public @interface LiveDescription {

    String message() default "방송 설명은 500자 이하여야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
