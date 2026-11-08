package com.sapari.streamingapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import com.sapari.chat.infrastructure.config.ChatMongoConfig;
import com.sapari.streamingapp.websocket.ChatSessionRegistry;
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
     * {@code Duration} 으로 바인딩되는 chat 키들.
     *
     * <p>⚠️ 접두를 섞어 담는다({@code chat.session.*} 셋 + {@code chat.mongo.operation-timeout}).
     * 한때 mongo 키를 별도 목록에 뒀는데 그러면 <b>Duration 전용 검사 둘을 빠져나간다</b> —
     * 빈 기본값 플레이스홀더 검사와 "템플릿이 그 키를 여전히 다룬다" 검사가 이 목록만 본다.
     * 이 목록의 기준은 키의 접두가 아니라 <b>바인딩 타입</b>이다.
     */
    private static final List<String> DURATION_KEYS =
            List.of("max-lifetime", "ping-interval", "liveness-deadline", "operation-timeout");

    /** {@code int} 로 바인딩되는 {@code chat.session.*} 키들. */
    private static final List<String> INT_KEYS = List.of("max-per-room");

    /**
     * 템플릿과 대조하는 {@code chat.session.*} 키 전부.
     *
     * <p>⚠️ <b>수기 목록이고, 한때 이 자리에 "오늘은 완전하다 … 네 번째가 생기면 크기 불일치로
     * 실패해 여기를 가리킨다" 가 적혀 있었는데 거짓이었다.</b> 실측: 템플릿에
     * {@code max-per-room} 을 넣고 값을 코드와 <b>다르게</b>(7 vs 5) 둬도 이 파일 전체가 초록이었다.
     * {@code documentedDefaults()} 가 이 목록으로 <b>걸러 내고</b> 있어서, 목록에 없는 키는
     * 크기에도 안 세어지기 때문이다. 즉 이 기계는 스스로를 가리키지 못한다 — 새 키를 여기에
     * <b>손으로</b> 더해야 비로소 묶인다.
     *
     * <p>그래서 완전성을 주장하지 않고 <b>범위</b>를 적는다: 아래 {@link #CHAT_VALUE_SOURCES} 의
     * 클래스들에 선언된 {@code chat.session.*} {@code @Value} 만 본다. 제3의 클래스에 같은 접두의
     * 키가 생기면 이 검사는 그것을 보지 못한다.
     */
    private static final List<String> CHAT_KEYS =
            Stream.concat(DURATION_KEYS.stream(), INT_KEYS.stream()).toList();

    /**
     * {@code chat.session.*} {@code @Value} 가 선언된 클래스들.
     *
     * <p>{@code WebSocketConfig} 는 수명 셋을 {@code @Bean} 파라미터로, {@code ChatSessionRegistry} 는
     * 상한을 <b>생성자</b> 파라미터로 받는다. 그래서 메서드만 훑으면 상한이 안 보인다.
     */
    private static final List<Class<?>> CHAT_VALUE_SOURCES =
            List.of(WebSocketConfig.class, ChatSessionRegistry.class, ChatMongoConfig.class);

    /**
     * 빈 기본값을 가진 플레이스홀더.
     *
     * <p>{@code endsWith(":}")} 로는 공백 기본값({@code ${X: }})을 못 본다 — env 가 없을 때 그 값도
     * 빈 문자열과 같은 실패로 간다. 그래서 {@code :} 뒤 공백까지 받는다.
     */
    private static final Pattern EMPTY_PLACEHOLDER_DEFAULT = Pattern.compile("\\$\\{[^}]*:\\s*}");

    /**
     * 이 파일이 덮는다고 선언한 접두의 {@code @Value} 모양.
     *
     * <p>{@code documentedDefaults()} 와 완전성 검사가 <b>같은</b> 패턴을 봐야 한다 — 서로 다른 집합을
     * 보면 둘 다 초록인 채로 어긋난다.
     */
    private static final Pattern COVERED_PREFIX_VALUE =
            Pattern.compile("\\$\\{chat\\.(?:session|mongo)\\.([a-z-]+):([^}]+)}");

    /** 주석 안에 적어 둔 코드 기본값 한 줄 — `#   max-lifetime: 60m   # …` 모양. */
    private static final Pattern DOCUMENTED_DEFAULT =
            Pattern.compile("^#\\s*([a-z-]+):\\s*(\\S+)");

    /**
     * 산문에 적어 둔 <b>탈출로 트리오</b> — `(max-lifetime 2m, ping-interval 20s, liveness-deadline 60s)`.
     *
     * <p>기본값 셋은 {@code # session:} 블록에서 뽑히지만 탈출로는 산문 한 줄이라 그 파서가 집지
     * 않는다. 그래서 불변식이 바뀌면 <b>템플릿의 보안 탈출로만 조용히 거부 띠로 들어간다</b> —
     * 그 자리가 "수명을 조이는 유일한 사인된 길" 이라 더 나쁘다. 여기서 뽑아 실제 가드에 넣는다.
     */
    private static final Pattern ESCAPE_TRIO = Pattern.compile(
            "\\(max-lifetime (\\S+), ping-interval (\\S+), liveness-deadline (\\S+)\\)");

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
        assertThat(documented).as("템플릿에서 코드 기본값 주석을 못 읽었다").hasSize(CHAT_KEYS.size());
        assertThat(fromCode).as("소스 클래스들에서 @Value 기본값을 못 읽었다").hasSize(CHAT_KEYS.size());
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
        // given
        Map<String, String> fromCode = codeDefaults();

        // when
        Duration interval = DurationStyle.detectAndParse(fromCode.get("ping-interval"));
        Duration deadline = DurationStyle.detectAndParse(fromCode.get("liveness-deadline"));

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
    @DisplayName("템플릿이 실제로 읽히고 Duration 키 전부를 여전히 다룬다 — 파일이 사라져도 위 검사가 통과하지 않게")
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
            // ⚠️ 키를 **잎 세그먼트**로만 잡는다 — chat.session.X 와 chat.mongo.X 가 같은 잎이면
            // 뒤에 읽은 쪽이 앞을 덮어쓰고 크기 검사도 통과한다. 오늘 겹치는 잎이 없어서
            // 분모가 비어 있을 뿐이고, 접두를 키에 넣는 쪽이 그걸 닫는다(이 티켓 밖).
            if (matcher.find() && CHAT_KEYS.contains(matcher.group(1))) {
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
        for (Class<?> source : CHAT_VALUE_SOURCES) {
            // 메서드와 생성자를 둘 다 본다 — 상한은 생성자 파라미터에 붙어 있어서,
            // 메서드만 훑던 때는 코드 쪽에서 그 키가 아예 나오지 않았다(실측: 분모 0).
            Stream.concat(Stream.of(source.getDeclaredMethods()), Stream.of(source.getDeclaredConstructors()))
                    .flatMap(executable -> Stream.of(executable.getParameters()))
                    .forEach(parameter -> {
                        Value value = parameter.getAnnotation(Value.class);
                        if (value == null) {
                            return;
                        }
                        // 접두를 session 하나로 못 박지 않는다 — 그랬으면 chat.mongo.* 키가 코드 쪽에서
                        // 아예 나오지 않아 크기 비교가 공허하게 통과한다(SPR-176 의 그 모양).
                        Matcher matcher = COVERED_PREFIX_VALUE.matcher(value.value());
                        if (matcher.matches()) {
                            defaults.put(matcher.group(1), matcher.group(2));
                        }
                    });
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


    /**
     * {@link #CHAT_VALUE_SOURCES} 의 <b>완전성</b>을 코드가 센다 — 명단이 수기라서 생긴 구멍을 닫는다.
     *
     * <p>위 {@code CHAT_KEYS} javadoc 이 적는 구멍이 이것이다 — *"제3의 클래스에 같은 접두의 키가
     * 생기면 이 검사는 그것을 보지 못한다."* 그 문장은 참이고, 이 칸이 그걸 거짓으로 만든다.
     *
     * <p>⚠️ <b>덮는 접두를 넓히지 않는다.</b> 오늘 {@code chat.*} 키는 여덟이고 다섯 클래스가 읽는데,
     * 그중 이 파일이 덮는다고 선언한 접두({@code chat.session.*} · {@code chat.mongo.*})는
     * <b>다섯 키 / 세 클래스이고 그 셋이 이미 명단에 있다</b>(실측, SPR-179). 나머지 셋은
     * {@code chat.profanity.*}(List 바인딩) · {@code chat.room-token.*}(필수 비밀키)로 <b>접두가
     * 다르다</b> — 명단에 넣으면 구멍이 닫히는 것이 아니라 이 파일의 주제가 바뀌고, Duration/int
     * 전용 검사 둘에 바인딩 타입이 다른 키가 끌려 들어온다. 그래서 주제는 그대로 두고 완전성만 닫는다.
     *
     * <p>⚠️ 이 칸은 오늘 초록이다(분모 0). 근거는 역변이다 — {@code chat.session.*} {@code @Value} 를
     * 가진 네 번째 클래스를 만들면 빨강이다.
     */
    @Test
    @DisplayName("⭐ 덮는 접두를 읽는 클래스가 명단 밖에 생기면 빨강 — 수기 명단의 완전성을 코드가 센다")
    void everyClassReadingTheCoveredPrefixesIsListed() {
        // given: com.sapari 전수를 훑는다. 범위를 패키지 하나로 두는 이유는 이 접두가 우리 코드에서만
        // 나오기 때문이고, 전수가 아니면 "못 봤다" 와 "없다" 의 출력이 같아진다.
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((metadataReader, factory) -> true);

        // when: 덮는 접두의 @Value 를 파라미터에 가진 클래스를 모은다. 추출 패턴은 documentedDefaults()
        // 가 쓰는 것과 같은 것이어야 한다 — 다르면 두 기계가 서로 다른 집합을 보고도 둘 다 초록이다.
        Set<Class<?>> declaring = new LinkedHashSet<>();
        for (var candidate : scanner.findCandidateComponents("com.sapari")) {
            Class<?> type;
            try {
                type = Class.forName(candidate.getBeanClassName());
            } catch (Throwable notOnThisClasspath) {
                continue;
            }
            boolean reads = Stream
                    .concat(Stream.of(type.getDeclaredMethods()), Stream.of(type.getDeclaredConstructors()))
                    .flatMap(executable -> Stream.of(executable.getParameters()))
                    .map(parameter -> parameter.getAnnotation(Value.class))
                    .anyMatch(value -> value != null && COVERED_PREFIX_VALUE.matcher(value.value()).matches());
            if (reads) {
                declaring.add(type);
            }
        }

        // then: 스캐너가 아무것도 못 찾았으면 이 단언이 먼저 깨진다 — 0건과 "못 봤다" 를 가른다.
        assertThat(declaring)
                .as("덮는 접두를 읽는 클래스 집합이 수기 명단과 다르다 — 명단에 더하거나, 접두를 안 쓰게 바꾼다")
                .containsExactlyInAnyOrderElementsOf(CHAT_VALUE_SOURCES);
    }

    /** 줄 끝 주석을 떼어 값만 남긴다 — `max-lifetime: ${X:}  # optional` 의 판정이 주석에 가리지 않게. */
    private static String withoutTrailingComment(String line) {
        int hash = line.indexOf('#');
        return (hash < 0 ? line : line.substring(0, hash)).strip();
    }
}
