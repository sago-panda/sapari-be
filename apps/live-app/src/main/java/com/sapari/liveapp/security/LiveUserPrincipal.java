package com.sapari.liveapp.security;

import java.util.UUID;

import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * live-app 인증 주체. access JWT claim(userId·role·nickname·email)을 담아 SecurityContext에 싣는다.
 *
 * <p>{@link AuthenticatedPrincipal#getName()}이 userId를 반환하므로, {@code authentication.getName()}에
 * 의존하는 공통 {@code CurrentUserIdArgumentResolver}가 그대로 동작한다(하위 호환). 룸 토큰 발급에 필요한
 * nickname/email/role은 {@code @AuthenticationPrincipal LiveUserPrincipal}로 꺼낸다.
 *
 * <p>email(PII)을 담으므로 이 객체를 로그에 남기지 않는다.
 */
public record LiveUserPrincipal(
        UUID userId,
        String role,
        String nickname,
        String email
) implements AuthenticatedPrincipal {

    @Override
    public String getName() {
        return userId.toString();
    }
}
