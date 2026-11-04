package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.user.model.UserRole;

/**
 * 강퇴가 읽는 <b>플랫폼 역할 이름</b>이 user 도메인과 갈라지지 않는지 빌드에서 대조한다.
 *
 * <p>{@code KickUserService}는 인증 주체가 준 역할 문자열을 자기 안의 열거로 파싱한다. user 도메인의 타입을
 * 직접 참조하지 않는 것은 의도다 — 그 의존이 생기면 이 경로를 얹은 앱이 계정 저장소 전체를 갖게 되고,
 * 그 포트에는 탈퇴 신청과 닉네임 변경이 함께 들어 있다.
 *
 * <p>대신 이름이 갈라질 위험이 남는다. 그리고 그 위험은 조용하지 않지만 <b>늦다</b> — user에 역할이 하나
 * 늘면 파싱이 실패하고, 정당한 권한자의 강퇴가 이유 없이 거부되는 형태로 운영에서야 드러난다.
 * 여기서 대조해 두면 빌드가 먼저 말한다.
 *
 * <p>운영 의존은 그대로 0이다 — {@code user-api}는 이 모듈의 <b>테스트</b> 스코프에만 있다.
 */
@DisplayName("플랫폼 역할 이름 — user 도메인과 갈라지면 빌드가 깨진다")
class PlatformRoleNamesTest {

    /** {@code KickUserService}의 private 열거. 이름 집합만 꺼내 온다. */
    private static Set<String> chatSideNames() throws Exception {
        Class<?> role = Class.forName("com.sapari.chat.application.service.KickUserService$UserRole");
        Method values = role.getDeclaredMethod("values");
        values.setAccessible(true);
        return Arrays.stream((Object[]) values.invoke(null))
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("이름 집합이 정확히 같다 — user에 역할이 늘면 여기가 먼저 깨진다")
    void nameSetsMatch() throws Exception {
        // given
        Set<String> platform = Arrays.stream(UserRole.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        // when & then
        assertThat(chatSideNames())
                .as("user 도메인의 역할 이름과 강퇴 경로가 읽는 이름이 다르다 — "
                        + "갈라지면 정당한 권한자의 강퇴가 런타임에 거부된다")
                .isEqualTo(platform);
    }
}
