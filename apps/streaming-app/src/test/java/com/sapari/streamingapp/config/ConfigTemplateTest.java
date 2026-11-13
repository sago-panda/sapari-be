package com.sapari.streamingapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.AnnotatedElement;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.yaml.snakeyaml.Yaml;

import com.sapari.streamingapp.websocket.SessionTimeouts;

/**
 * 설정 템플릿이 "그대로 복사하면 뜨는" 상태인지, 그리고 적어 둔 기본값이 실제 기본값인지 본다.
 *
 * <p>이 파일은 스스로 {@code cp application.yml.example application.yml} 을 지시한다. 그래서 여기
 * 적힌 모양이 곧 다음 사람의 부팅이고, 틀리면 그 사람에게만 터진다 — 이 저장소에서 돌아가는 테스트는
 * 추적되지 않는 각자의 {@code application.yml} 을 쓰므로 아무것도 알려주지 않는다.
 *
 * <p><b>대조 대상을 손으로 적지 않는다.</b> 코드 쪽은 이 클래스패스의 {@code com.sapari} 전체에서
 * 기본값이 있는 {@code chat.*} {@code @Value} 를 모으고, 템플릿 쪽은 {@code chat:} 절의
 * "코드 기본값" 주석을 모아 <b>전체 경로</b>로 집합째 맞춘다. 전에는 키·접두·클래스 목록이 수기였고,
 * 목록 밖의 {@code chat.withdrawal.record-ttl} 은 템플릿이 12h · 코드가 24h 여도 이 파일 전체가
 * 초록이었다(SPR-191 실측).
 */
@DisplayName("application.yml.example — 그대로 복사해도 부팅을 막지 않는다")
class ConfigTemplateTest {

    /** {@code chat.*} {@code @Value} 한 개 — 키 전체 경로와 기본값. 기본값이 없으면(:} 로 끝나면) 빈 문자열. */
    private static final Pattern CHAT_VALUE = Pattern.compile("\\$\\{(chat\\.[a-z.-]+):([^}]*)}");

    /**
     * 빈 기본값을 가진 플레이스홀더.
     *
     * <p>{@code endsWith(":}")} 로는 공백 기본값({@code ${X: }})을 못 본다 — env 가 없을 때 그 값도
     * 빈 문자열과 같은 실패로 간다. 그래서 {@code :} 뒤 공백까지 받는다.
     */
    private static final Pattern EMPTY_PLACEHOLDER_DEFAULT = Pattern.compile("\\$\\{[^}]*:\\s*}");

    /** 주석 안의 키 한 줄 — `#   max-lifetime: 60m   # 코드 기본값 …` 또는 `# session:`. 들여쓰기가 깊이다. */
    private static final Pattern COMMENTED_KEY = Pattern.compile("^( *)([a-z-]+):\\s*(\\S*)");

    /** 템플릿 범례가 "그 코드 기본값이다" 라고 약속하는 줄의 표지. */
    private static final String CODE_DEFAULT_MARK = "코드 기본값";

    /**
     * 산문에 적어 둔 <b>탈출로 트리오</b> — `(max-lifetime 2m, ping-interval 20s, liveness-deadline 60s)`.
     *
     * <p>기본값 셋은 {@code # session:} 블록에서 뽑히지만 탈출로는 산문 한 줄이라 그 파서가 집지
     * 않는다. 그래서 불변식이 바뀌면 <b>템플릿의 보안 탈출로만 조용히 거부 띠로 들어간다</b> —
     * 그 자리가 "수명을 조이는 유일한 사인된 길" 이라 더 나쁘다. 여기서 뽑아 실제 가드에 넣는다.
     */
    private static final Pattern ESCAPE_TRIO = Pattern.compile(
            "\\(max-lifetime (\\S+), ping-interval (\\S+), liveness-deadline (\\S+)\\)");

    /** 코드의 {@code chat.*} 기본값 — 키 전체 경로 → 값. 클래스패스 전수 스캔이라 한 번만 짓는다. */
    private static Map<String, String> codeDefaults;

    @BeforeAll
    static void scanCodeDefaults() {
        codeDefaults = codeDefaultsOnClasspath();
    }

    @Test
    @DisplayName("⭐ 코드 기본값이 있는 키에 빈 기본값(${VAR:})을 두지 않는다 — 빈 문자열이 코드 기본값을 밀어낸다")
    void code_default_keys_are_not_given_an_empty_placeholder_default() throws IOException {
        // given: 주석을 뺀 실제 설정을 전체 경로로 편다 — 중첩 모양과 점 표기 평면 키가 같은 경로가 된다
        Map<String, String> active = activeChatEntries();
        assertThat(active).as("템플릿의 chat: 절을 못 읽었다 — 비면 아래가 공허하게 통과한다").isNotEmpty();

        // when
        List<String> offending = active.entrySet().stream()
                .filter(entry -> codeDefaults.containsKey(entry.getKey()))
                .filter(entry -> EMPTY_PLACEHOLDER_DEFAULT.matcher(entry.getValue()).find())
                .map(Map.Entry::getKey)
                .toList();

        // then: env 가 없으면 ${VAR:} 는 "미설정"이 아니라 빈 문자열이고, 그러면 @Value 의 기본값이
        // 쓰이지 않는다 — Duration 은 null 이 되어(실측 — 키 부재면 60m) SessionTimeouts 가 거부하고,
        // int 는 변환에 실패한다. 템플릿을 지시대로 복사한 사람은 부팅이 막힌다.
        assertThat(offending)
                .as("빈 기본값을 가진 키 — 주석 처리하거나 ${VAR:60m} 처럼 기본값을 안에 둘 것")
                .isEmpty();
    }

    @Test
    @DisplayName("⭐ 템플릿에 적힌 코드 기본값이 실제 @Value 기본값과 같다 — 키 집합째, 전체 경로로")
    void documented_defaults_match_the_code_defaults() throws IOException {
        // given: 범례가 "optional — 여기 적힌 건 그 코드 기본값이다" 라고 약속한다. 운영자가 특히
        // max-lifetime 을 그 주석으로 판단한다("접속 후 바뀐 권위가 반영되기까지의 상한").
        Map<String, String> documented = documentedDefaults();

        // when & then: 한쪽을 못 읽으면 비교가 공허하게 통과한다(양쪽 다 비면 isEqualTo 가 성립한다).
        // 키가 한쪽에만 있어도 깨진다 — 코드에 기본값 키를 더하면 템플릿에도 적어야 한다.
        assertThat(documented).as("템플릿에서 코드 기본값 주석을 못 읽었다").isNotEmpty();
        assertThat(codeDefaults).as("클래스패스에서 chat.* @Value 기본값을 못 읽었다").isNotEmpty();
        assertThat(documented).as("템플릿 주석과 코드 기본값이 갈렸다").isEqualTo(codeDefaults);
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
                DurationStyle.detectAndParse(documented.get("chat.session.max-lifetime")),
                DurationStyle.detectAndParse(documented.get("chat.session.ping-interval")),
                DurationStyle.detectAndParse(documented.get("chat.session.liveness-deadline"))))
                .as("템플릿이 적은 세 값이 SessionTimeouts 에서 거부된다 — 그대로 쓰면 부팅이 막힌다")
                .doesNotThrowAnyException();
    }

    /**
     * ⭐ <b>코드 기본값</b>도 기한/간격 3배를 유지한다.
     *
     * <p>부팅 가드는 2배까지 허용하므로 가드만으로는 이 비율이 지켜지지 않는다. 그리고
     * {@code documented_defaults_match_the_code_defaults} 는 둘이 <b>같은지</b>만 보므로, 코드 기본값과
     * 템플릿 주석을 <b>함께</b> 60s 로 내리면 두 테스트가 모두 초록인 채 연속 PONG 유실 내성이
     * 2회에서 1회로 깎인다 — 산문 탈출로에서 두 번 걸려 고친 결함이 기본값 쪽으로 재도입되는 길이다.
     *
     * <p>그래서 비율을 값 자체에 대고 단언한다. 산문 탈출로 쪽은 {@code ConfigTemplateTest} 의
     * 탈출로 테스트가 같은 단언을 따로 갖는다.
     */
    @Test
    @DisplayName("⭐ 코드 기본값도 기한/간격 3배를 유지한다 — 2배 가드만으로는 내성이 깎인 채 초록이다")
    void code_defaults_keep_the_three_times_ratio() {
        // when
        Duration interval = DurationStyle.detectAndParse(codeDefaults.get("chat.session.ping-interval"));
        Duration deadline = DurationStyle.detectAndParse(codeDefaults.get("chat.session.liveness-deadline"));

        // then
        assertThat(deadline)
                .as("코드 기본 기한이 간격의 3배 미만이다 — 연속 유실 내성이 2회에서 1회로 깎인다")
                .isGreaterThanOrEqualTo(interval.multipliedBy(3));
    }

    /**
     * ⭐ 산문에 적어 둔 탈출로가 가드를 통과하고 기본값과 같은 내성을 갖는다.
     *
     * <p>수명만 내리는 길이 가드에 막혀 있으므로 템플릿이 탈출로를 하나 적어 둔다. 통과만으로는
     * 부족하다 — 기한/간격 비가 연속 유실 내성을 정하기 때문이다. 판정이 {@code now - last >= 기한}
     * 이라 k회 연속 유실이 {@code k × 간격} 을 만들고 그것이 기한보다 작아야 사므로, 비율 3배면
     * 2회이고 <b>2배면 1회</b>다. 가드는 2배까지 허용하므로 그 단언을 따로 둔다.
     *
     * <p>값을 테스트에 적지 않고 <b>템플릿 문장에서 뽑는다</b>. 적어 두면 템플릿이 바뀌어도 테스트가
     * 자기 값만 보고 초록이 된다 — 끈이 없으면 고정이 아니다.
     */
    @Test
    @DisplayName("⭐ 산문의 탈출로 트리오가 가드를 통과하고 3배 비율을 유지한다 — 템플릿에서 뽑아 넣는다")
    void the_documented_escape_trio_still_passes_the_guard() throws IOException {
        // given: 산문에서 트리오를 뽑는다
        Matcher matcher = ESCAPE_TRIO.matcher(template());
        assertThat(matcher.find()).as("템플릿 산문에서 탈출로 트리오를 찾지 못했다").isTrue();
        Duration lifetime = DurationStyle.detectAndParse(matcher.group(1));
        Duration interval = DurationStyle.detectAndParse(matcher.group(2));
        Duration deadline = DurationStyle.detectAndParse(matcher.group(3));

        // when & then: 실제 가드에 넣는다
        assertThatCode(() -> new SessionTimeouts(lifetime, interval, deadline))
                .as("템플릿이 적은 탈출로가 부팅 가드에서 거부된다 — 그 길을 따라간 사람이 막힌다")
                .doesNotThrowAnyException();

        // 그리고 비율이 기본값과 같다
        assertThat(deadline).as("기한/간격 비가 3배 미만이면 연속 유실 내성이 2회에서 1회로 깎인다")
                .isGreaterThanOrEqualTo(interval.multipliedBy(3));
    }

    @Test
    @DisplayName("템플릿이 실제로 읽힌다 — 파일이 사라져도 위 검사가 통과하지 않게")
    void template_is_actually_read() throws IOException {
        // when & then: 파일이 비면 위 검사는 "위반 0건"으로 조용히 통과한다. 그 공허한 초록을 막는다.
        assertThat(template()).contains("cp application.yml.example");
    }

    /**
     * 템플릿 {@code chat:} 절의 "코드 기본값" 주석 — 키 전체 경로 → 값.
     *
     * <p>주석 블록({@code # session:} 아래 {@code #   max-lifetime: 60m}) 의 들여쓰기로 경로를 짓는다.
     * 전에는 잎 이름만 키로 써서 {@code chat.session.X} 와 {@code chat.mongo.X} 가 같은 잎이면 뒤가
     * 앞을 덮을 수 있었다. 주석이 아닌 줄을 만나면 블록이 끝난 것으로 본다.
     */
    private Map<String, String> documentedDefaults() throws IOException {
        Map<String, String> defaults = new LinkedHashMap<>();
        Deque<Map.Entry<Integer, String>> parents = new ArrayDeque<>();
        boolean inChat = false;
        for (String line : template().lines().toList()) {
            if (!line.isBlank() && !Character.isWhitespace(line.charAt(0)) && !line.startsWith("#")) {
                inChat = line.startsWith("chat:");
                parents.clear();
                continue;
            }
            String stripped = line.strip();
            if (!inChat || stripped.isEmpty()) {
                continue;
            }
            if (!stripped.startsWith("#")) {
                parents.clear();
                continue;
            }
            Matcher matcher = COMMENTED_KEY.matcher(stripped.substring(1));
            if (!matcher.find()) {
                continue;
            }
            int depth = matcher.group(1).length();
            while (!parents.isEmpty() && parents.peek().getKey() >= depth) {
                parents.pop();
            }
            String value = matcher.group(3);
            if (value.isEmpty() || value.startsWith("#")) {
                parents.push(Map.entry(depth, matcher.group(2)));
            } else if (line.contains(CODE_DEFAULT_MARK)) {
                StringBuilder path = new StringBuilder("chat");
                parents.descendingIterator().forEachRemaining(parent -> path.append('.').append(parent.getValue()));
                defaults.put(path.append('.').append(matcher.group(2)).toString(), value);
            }
        }
        return defaults;
    }

    /** 템플릿의 실제(주석 아닌) 설정을 전체 경로로 편 것 중 {@code chat.} 으로 시작하는 것. */
    private Map<String, String> activeChatEntries() throws IOException {
        Map<String, String> flat = new LinkedHashMap<>();
        flatten("", new Yaml().load(template()), flat);
        flat.keySet().removeIf(key -> !key.startsWith("chat."));
        return flat;
    }

    private static void flatten(String prefix, Object node, Map<String, String> into) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, child) -> flatten(prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key, child, into));
        } else {
            into.put(prefix, String.valueOf(node));
        }
    }

    /**
     * 이 클래스패스의 {@code com.sapari} 전체에서 기본값이 있는 {@code chat.*} {@code @Value} — 키 → 값.
     *
     * <p>메서드·생성자 파라미터와 필드를 다 본다. 상한({@code max-per-room})은 생성자 파라미터에 붙어
     * 있어서, 메서드만 훑던 때는 코드 쪽에서 그 키가 아예 나오지 않았다(실측: 분모 0).
     * 기본값이 없는 키({@code chat.room-token.public-key} 등)는 "코드 기본값" 이 없으므로 뺀다.
     */
    private static Map<String, String> codeDefaultsOnClasspath() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((metadataReader, factory) -> true);

        Map<String, String> defaults = new LinkedHashMap<>();
        for (var candidate : scanner.findCandidateComponents("com.sapari")) {
            Class<?> type;
            try {
                type = Class.forName(candidate.getBeanClassName());
            } catch (Throwable notOnThisClasspath) {
                continue;
            }
            Stream.of(
                            Stream.of(type.getDeclaredMethods()).flatMap(method -> Stream.of(method.getParameters())),
                            Stream.of(type.getDeclaredConstructors()).flatMap(ctor -> Stream.of(ctor.getParameters())),
                            Stream.of(type.getDeclaredFields()))
                    .flatMap(stream -> stream.map(AnnotatedElement.class::cast))
                    .map(element -> element.getAnnotation(Value.class))
                    .filter(value -> value != null)
                    .map(value -> CHAT_VALUE.matcher(value.value()))
                    .filter(Matcher::matches)
                    .filter(matcher -> !matcher.group(2).isBlank())
                    .forEach(matcher -> defaults.put(matcher.group(1), matcher.group(2)));
        }
        return defaults;
    }

    private String template() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/application.yml.example")) {
            assertThat(stream).as("/application.yml.example 를 클래스패스에서 찾지 못했다").isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
