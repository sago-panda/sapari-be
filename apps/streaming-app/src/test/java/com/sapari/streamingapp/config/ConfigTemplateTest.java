package com.sapari.streamingapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;

import com.sapari.streamingapp.websocket.SessionTimeouts;

/**
 * 설정 템플릿이 "그대로 복사하면 뜨는" 상태인지, 그리고 적어 둔 기본값이 실제 기본값인지 본다.
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
     * <p>수기 목록이고 오늘은 완전하다 — {@code Duration} 타깃 {@code @Value} 는 아래
     * {@code codeDefaults()} 가 읽는 셋뿐이다. 네 번째가 생기면 그 메서드가 넷을 돌려주므로
     * {@code documented_defaults_match_the_code_defaults} 가 크기 불일치로 실패해 여기를 가리킨다.
     */
    private static final List<String> DURATION_KEYS =
            List.of("max-lifetime", "ping-interval", "liveness-deadline");

    /**
     * 빈 기본값을 가진 플레이스홀더.
     *
     * <p>{@code endsWith(":}")} 로는 공백 기본값({@code ${X: }})을 못 본다 — env 가 없을 때 그 값도
     * 빈 문자열과 같은 실패로 간다. 그래서 {@code :} 뒤 공백까지 받는다.
     */
    private static final Pattern EMPTY_PLACEHOLDER_DEFAULT = Pattern.compile("\\$\\{[^}]*:\\s*}");

    /** 주석 안에 적어 둔 코드 기본값 한 줄 — `#   max-lifetime: 60m   # …` 모양. */
    private static final Pattern DOCUMENTED_DEFAULT =
            Pattern.compile("^#\\s*([a-z-]+):\\s*(\\S+)");

    @Test
    @DisplayName("⭐ Duration 키에 빈 기본값(${VAR:})을 두지 않는다 — 빈 문자열이 코드 기본값을 밀어낸다")
    void duration_keys_are_not_given_an_empty_placeholder_default() throws IOException {
        // given
        String template = template();

        // when: 주석이 아닌 줄 중 Duration 키에 빈 기본값 플레이스홀더를 대입한 것
        List<String> offending = template.lines()
                .map(String::strip)
                .filter(line -> !line.startsWith("#"))
                .filter(ConfigTemplateTest::assignsADurationKey)
                .filter(line -> EMPTY_PLACEHOLDER_DEFAULT.matcher(withoutTrailingComment(line)).find())
                .toList();

        // then: env 가 없으면 ${VAR:} 는 "미설정"이 아니라 빈 문자열이고, 그러면 @Value 의 기본값이
        // 쓰이지 않아 Duration 이 null 이 된다(실측 — 키 부재면 60m). SessionTimeouts 가 null 을
        // 거부하므로 템플릿을 지시대로 복사한 사람은 부팅이 막힌다.
        assertThat(offending)
                .as("빈 기본값을 가진 Duration 키 — 주석 처리하거나 ${VAR:60m} 처럼 기본값을 안에 둘 것")
                .isEmpty();
    }

    @Test
    @DisplayName("⭐ 템플릿에 적힌 코드 기본값이 실제 @Value 기본값과 같다 — 이 파일이 그렇게 약속한다")
    void documented_defaults_match_the_code_defaults() throws IOException {
        // given: 범례가 "optional — 여기 적힌 건 그 코드 기본값이다" 라고 약속한다. 운영자가 특히
        // max-lifetime 을 그 주석으로 판단한다("접속 후 바뀐 권위가 반영되기까지의 상한").
        Map<String, String> documented = documentedDefaults();
        Map<String, String> fromCode = codeDefaults();

        // when & then: 둘이 갈리면 약속이 거짓이 된다. 크기를 먼저 본다 — 한쪽을 못 읽으면
        // 비교가 공허하게 통과한다(양쪽 다 비면 isEqualTo 가 성립한다).
        assertThat(documented).as("템플릿에서 코드 기본값 주석을 못 읽었다").hasSize(DURATION_KEYS.size());
        assertThat(fromCode).as("WebSocketConfig 에서 @Value 기본값을 못 읽었다").hasSize(DURATION_KEYS.size());
        assertThat(documented).as("템플릿 주석과 코드 기본값이 갈렸다").isEqualTo(fromCode);
    }

    /**
     * ⭐ 템플릿이 적은 기본값 트리오가 {@link SessionTimeouts} 가드를 통과한다.
     *
     * <p>세 값은 서로 제약하고 그 제약이 직관과 어긋난다 — 지터를 뺀 최소 수명이 기한 + 간격보다
     * 커야 해서, 기본값에서 실제 하한은 "기한보다 크면 된다"가 아니라 {@code max-lifetime > 141s} 다.
     * 설명이 거부되는 조합을 예시로 들면 그대로 복사한 사람이 부팅에서 막힌다.
     */
    @Test
    @DisplayName("⭐ 템플릿이 적은 기본값 트리오가 부팅 가드를 통과한다 — 설명이 거부되는 조합을 가리키지 않게")
    void documented_defaults_satisfy_the_boot_guard() throws IOException {
        // given
        Map<String, String> documented = documentedDefaults();

        // when & then
        assertThatCode(() -> new SessionTimeouts(
                DurationStyle.detectAndParse(documented.get("max-lifetime")),
                DurationStyle.detectAndParse(documented.get("ping-interval")),
                DurationStyle.detectAndParse(documented.get("liveness-deadline"))))
                .as("템플릿이 적은 세 값이 SessionTimeouts 에서 거부된다 — 그대로 쓰면 부팅이 막힌다")
                .doesNotThrowAnyException();
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

    /** 템플릿 주석에 적힌 기본값 — 키 → 값. */
    private Map<String, String> documentedDefaults() throws IOException {
        Map<String, String> defaults = new LinkedHashMap<>();
        template().lines().map(String::strip).filter(line -> line.startsWith("#")).forEach(line -> {
            Matcher matcher = DOCUMENTED_DEFAULT.matcher(line.substring(1).strip().startsWith("#")
                    ? line.substring(1).strip() : line);
            if (matcher.find() && DURATION_KEYS.contains(matcher.group(1))) {
                defaults.put(matcher.group(1), matcher.group(2));
            }
        });
        return defaults;
    }

    /**
     * {@code WebSocketConfig} 의 {@code @Value} 기본값 — 키 → 값.
     *
     * <p>자바 소스를 읽지 않고 리플렉션으로 가져온다. {@code @Value} 는 {@code RetentionPolicy.RUNTIME}
     * 이고, 소스 파일을 경로로 읽으면 테스트 작업 디렉터리에 묶인다.
     */
    private Map<String, String> codeDefaults() {
        Map<String, String> defaults = new LinkedHashMap<>();
        // 반환형으로 거르지 않는다 — 한때 ChatWebSocketHandler 를 돌려주는 메서드만 봤는데, 그러면
        // 같은 클래스의 다른 메서드에 Duration @Value 를 더했을 때 이 검사에 안 보이고
        // DURATION_KEYS 가 조용히 낡는다(실측으로 그 뮤턴트가 생존했다).
        for (Method method : WebSocketConfig.class.getDeclaredMethods()) {
            for (Parameter parameter : method.getParameters()) {
                Value value = parameter.getAnnotation(Value.class);
                if (value == null) {
                    continue;
                }
                Matcher matcher = Pattern.compile("\\$\\{chat\\.session\\.([a-z-]+):([^}]+)}")
                        .matcher(value.value());
                if (matcher.matches()) {
                    defaults.put(matcher.group(1), matcher.group(2));
                }
            }
        }
        return defaults;
    }

    private String template() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/application.yml.example")) {
            assertThat(stream).as("/application.yml.example 를 클래스패스에서 찾지 못했다").isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 그 줄이 Duration 키에 값을 대입하는지.
     *
     * <p>점 표기 평면 키({@code chat.session.max-lifetime: …})도 받는다 — 마지막 세그먼트만 보면
     * 그 모양이 검사를 통째로 비껴간다.
     */
    private static boolean assignsADurationKey(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String key = line.substring(0, colon).strip();
        return DURATION_KEYS.stream().anyMatch(known -> key.equals(known) || key.endsWith("." + known));
    }

    /** 줄 끝 주석을 떼어 값만 남긴다 — `max-lifetime: ${X:}  # optional` 의 판정이 주석에 가리지 않게. */
    private static String withoutTrailingComment(String line) {
        int hash = line.indexOf('#');
        return (hash < 0 ? line : line.substring(0, hash)).strip();
    }
}
