package com.sapari.common.web.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 인증된 사용자의 userId(UUID)를 컨트롤러 파라미터로 주입한다.
 * {@link CurrentUserIdArgumentResolver}가 SecurityContext의 인증 주체에서 해석한다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUserId {
}
