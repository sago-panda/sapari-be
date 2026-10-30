package com.sapari.streamingapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 설정 템플릿이 "그대로 복사하면 뜨는" 상태인지 본다.
 *
 * <p>이 파일은 스스로 {@code cp application.yml.example application.yml} 을 지시한다. 그래서 여기
 * 적힌 모양이 곧 다음 사람의 부팅이고, 틀리면 그 사람에게만 터진다 — 이 저장소에서 돌아가는 테스트는
 * 추적되지 않는 각자의 {@code application.yml} 을 쓰므로 아무것도 알려주지 않는다.
 */
@DisplayName("application.yml.example — 그대로 복사해도 부팅을 막지 않는다")
class ConfigTemplateTest {

    /**
     * {@code Duration} 으로 바인딩되는 키들.
     *
     * <p>{@code WebSocketConfig} 가 {@code @Value("${chat.session.*:기본값}") Duration} 으로 받는다.
     * Duration 키가 늘면 여기에도 더할 것 — 이 목록이 검사 범위다.
     */
    private static final List<String> DURATION_KEYS =
            List.of("max-lifetime", "ping-interval", "liveness-deadline");

    @Test
    @DisplayName("⭐ Duration 키에 빈 기본값(${VAR:})을 두지 않는다 — 빈 문자열이 코드 기본값을 밀어낸다")
    void duration_keys_are_not_given_an_empty_placeholder_default() throws IOException {
        // given
        String template = template();

        // when: 주석이 아닌 줄 중 Duration 키에 ${...:} 를 대입한 것
        List<String> offending = template.lines()
                .map(String::strip)
                .filter(line -> !line.startsWith("#"))
                .filter(line -> DURATION_KEYS.stream().anyMatch(key -> line.startsWith(key + ":")))
                .filter(line -> withoutTrailingComment(line).endsWith(":}"))
                .toList();

        // then: env 가 없으면 ${VAR:} 는 "미설정"이 아니라 빈 문자열이고, 그러면
        // @Value 의 기본값이 쓰이지 않아 Duration 이 null 이 된다(실측 — 키 부재면 60m).
        // SessionTimeouts 가 null 을 거부하므로 템플릿을 지시대로 복사한 사람은 부팅이 막힌다.
        // 코드 기본값을 쓰려면 그 줄을 주석 처리하고, env 로 주입하려면 기본값을 플레이스홀더 안에 둘 것.
        assertThat(offending)
                .as("빈 기본값을 가진 Duration 키 — 주석 처리하거나 ${VAR:60m} 처럼 기본값을 안에 둘 것")
                .isEmpty();
    }

    @Test
    @DisplayName("템플릿이 실제로 읽히고 그 세 키를 여전히 다룬다 — 파일이 사라져도 위 검사가 통과하지 않게")
    void template_is_actually_read() throws IOException {
        // given
        String template = template();

        // when & then: 파일이 비거나 키 이름이 바뀌면 위 검사는 "위반 0건"으로 조용히 통과한다.
        // 그 공허한 초록을 막는 것이 이 테스트의 전부다.
        assertThat(template).contains("cp application.yml.example");
        assertThat(DURATION_KEYS).allSatisfy(key -> assertThat(template).contains(key));
    }

    private String template() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/application.yml.example")) {
            assertThat(stream).as("/application.yml.example 를 클래스패스에서 찾지 못했다").isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 줄 끝 주석을 떼어 값만 남긴다 — `max-lifetime: ${X:}  # optional` 의 판정이 주석에 가리지 않게. */
    private static String withoutTrailingComment(String line) {
        int hash = line.indexOf('#');
        return (hash < 0 ? line : line.substring(0, hash)).strip();
    }
}
