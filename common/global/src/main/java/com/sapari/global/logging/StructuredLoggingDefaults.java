package com.sapari.global.logging;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 모든 앱의 로그를 ECS JSON 으로 내보내는 <b>기본값</b>을 심는다.
 *
 * <p>왜 이 저장소에 필요한가: 로그 설정이 있는 앱이 {@code api-app} 하나였고 그것도 plaintext 였다.
 * 나머지 넷은 부트 기본 패턴이었는데 그 패턴에는 MDC 항이 없어서 — 실측: 기본 콘솔 패턴의
 * {@code LOG_CORRELATION_PATTERN} 이 빈 값이다 — {@code live-app} 은 {@code requestId}·{@code userId}
 * 를 MDC 에 넣고도 <b>출력에 남기지 못했다</b>. 코드는 돌고 결과만 없는 상태였다.
 *
 * <p>ECS 를 고른 이유는 <b>MDC 가 그대로 최상위 JSON 필드가 되기 때문</b>이다(실측). 패턴을 앱마다
 * 손볼 필요가 없어지고, 위의 "넣었는데 안 찍힌다" 가 구조적으로 불가능해진다. 추가 의존은 없다 —
 * Spring Boot 4 가 내장한다.
 *
 * <p><b>왜 {@code application.properties} 가 아니라 여기인가.</b> 운영 설정은 {@code application*.yml}
 * 이고 그 파일은 추적되지 않는다. 같은 자리에 커밋 가능한 {@code application.properties} 를 두면
 * <b>그쪽이 운영 yml 을 이긴다</b>(실측) — 커밋된 기본값이 운영자의 설정을 조용히 덮어쓴다.
 * 반대로 기본 프로퍼티 소스는 yml 에 <b>진다</b>(실측). 기본값이 가져야 하는 성질이 후자다.
 *
 * <p>{@code EnvironmentPostProcessor} 인 것도 필요 조건이다 — 로깅 시스템은 환경이 준비된 직후
 * 초기화되므로, 빈으로는 이미 늦다.
 *
 * <p>⚠️ <b>인터페이스와 등록 키가 모두 {@code org.springframework.boot.EnvironmentPostProcessor} 여야
 * 한다</b> ({@code ...boot.env.} 이 아니다). Boot 4 에 같은 모양의 인터페이스가 두 경로에 공존해서
 * 구 경로로 써도 컴파일이 통과한다.
 *
 * <p><b>등록 파일은 {@code META-INF/spring.factories} 다</b> — Boot 4 의 자동설정이 쓰는
 * {@code .imports} 가 아니다. {@code EnvironmentPostProcessor} 를 읽는 유일한 로더가
 * {@code spring.factories} 쪽이고, Boot 자신도 자기 EPP 들을 같은 키로 거기에 등록한다.
 * <b>진짜 조용한 실패는 이 파일 이름 쪽이다</b> — {@code .imports} 에 적으면 아무도 읽지 않으므로
 * 에러 없이 이 클래스만 안 돈다.
 *
 * <p><b>실측(Boot 4.0.6)</b>: 신 인터페이스를 구 키({@code ...boot.env.EnvironmentPostProcessor})로
 * 등록하면 <b>조용하지 않다 — 던진다</b>:
 * {@code IllegalArgumentException: Unable to instantiate factory class [...] for factory type
 * [org.springframework.boot.env.EnvironmentPostProcessor]}. 한때 이 자리에 "구 경로로 써도 조용히 안
 * 돈다" 고 적혀 있었는데 그건 틀렸다. 같은 조합으로 {@code StructuredLoggingEndToEndTest} 2건도
 * 깨진다(실측) — 즉 이 오등록은 그물이 둘이다.
 */
public class StructuredLoggingDefaults implements EnvironmentPostProcessor {

    /** 기본 프로퍼티 소스 이름 — 진단 시 "이 값이 어디서 왔나" 를 환경 덤프에서 바로 읽게 한다. */
    static final String SOURCE_NAME = "sapariStructuredLoggingDefaults";

    /**
     * 스택트레이스 길이 상한.
     *
     * <p>ECS 는 스택트레이스를 <b>전 프레임</b> 싣는다(실측: 프레임워크 프레임까지 들어가 한 줄이
     * 수 KB 가 된다). 로그 한 줄의 비용이 수집·보관 비용이라 상한이 없으면 장애 중에 그 비용이
     * 폭증한다 — 그리고 장애 중에 가장 보고 싶은 것이 로그다.
     */
    private static final int STACK_TRACE_MAX_LENGTH = 4096;

    /** 프레임 깊이 상한. 원인 사슬 하나당 이 개수까지 — 우리 프레임은 한참 아래다. */
    private static final int STACK_TRACE_MAX_DEPTH = 24;

    private static final String APPLICATION_CLASS_SUFFIX = "Application";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("logging.structured.format.console", "ecs");

        // root=last: 가장 바깥 예외가 아니라 <b>근본 원인</b>을 먼저 쓴다. 상한에 걸려 잘릴 때
        // 남는 쪽이 원인이어야 한다 — 잘린 로그에서 래퍼만 남으면 아무 진단이 안 된다.
        defaults.put("logging.structured.json.stacktrace.root", "last");
        defaults.put("logging.structured.json.stacktrace.max-length", STACK_TRACE_MAX_LENGTH);
        defaults.put("logging.structured.json.stacktrace.max-throwable-depth", STACK_TRACE_MAX_DEPTH);
        // 공통 프레임(원인 사슬이 공유하는 꼬리)은 뺀다 — 같은 프레임을 사슬마다 반복해 싣는다.
        defaults.put("logging.structured.json.stacktrace.include-common-frames", false);

        deriveApplicationName(application).ifPresent(name -> defaults.put("spring.application.name", name));

        // addLast: 설정 파일·환경변수·커맨드라인이 모두 이것을 이긴다.
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, defaults));
    }

    /**
     * 앱 이름을 만든다 — {@code StreamingAppApplication} → {@code streaming-app}.
     *
     * <p>이게 필요한 이유: {@code spring.application.name} 이 이 저장소에 <b>한 곳도 설정돼 있지
     * 않았다</b>. ECS 의 {@code service.name} 이 그 값에서 오므로, 비워 두면 다섯 앱의 로그가 한
     * 스트림에 섞인 뒤 <b>어느 앱이 쓴 줄인지 알 수 없다</b>. 그 구분이 없으면 JSON 으로 바꾼 이득의
     * 절반이 사라진다.
     *
     * <p>앱마다 손으로 적게 하지 않는 이유는 그 다섯 줄이 전부 추적되지 않는 yml 로 가기 때문이다 —
     * 복사하지 않은 환경에서 조용히 비는 모양이고, 이 저장소가 그 함정을 이미 두 번 겪었다.
     *
     * <p>⚠️ <b>{@code getMainApplicationClass()} 를 쓰지 않는다.</b> 그 값은 생성 시점의 스택트레이스에서
     * {@code main} 을 찾아 추론한 것이라 실행 방식에 따라 엉뚱한 클래스가 된다 — 실측: Gradle 테스트
     * 워커에서는 {@code GradleWorkerMain} 이 나온다. 그러면 운영에서는 맞고 테스트에서는 틀려서
     * <b>이 유도가 작동하는지 검증할 방법이 없어진다</b>. {@code getAllSources()} 는 두 경우 모두
     * {@code @SpringBootApplication} 클래스를 돌려준다(실측).
     *
     * <p>{@code Application} 로 끝나는 클래스가 <b>정확히 하나</b>일 때만 짓는다. 여럿이거나 없으면
     * 짓지 않는다 — 추측한 이름이 붙는 것보다 비어 있는 편이 낫다. 틀린 이름은 그게 틀렸다는 사실조차
     * 드러나지 않는 반면, 빈 값은 {@code spring.application.name} 을 설정으로 채우면 끝이다.
     *
     * <p>⚠️ 한때 그 근거를 "<b>비어 있으면 눈에 보인다</b>" 로 적었는데, 그걸 보이게 할 소비자가 이
     * 저장소에 없다 — 로그 적재·수집기 매니페스트 0건, {@code infra/AGENTS.md} 의 OpenSearch 언급은
     * 공유 상태 한 줄이고 로그 파이프라인과 무관하다({@code LogFields} 자신도 "아직 수집기가 붙어
     * 있지 않다" 고 적는다). 빈 {@code service.name} 이 드롭되는지 {@code unknown} 으로 색인되는지는
     * 여기서 답할 수 없다. 그래서 이 결정의 근거는 "보인다" 가 아니라 <b>"소비자가 붙는 날 확인한다"</b>
     * 다 — 결정 자체는 그대로 둔다(틀린 이름이 붙는 쪽이 여전히 더 나쁘다).
     */
    private static java.util.Optional<String> deriveApplicationName(SpringApplication application) {
        if (application == null) {
            return java.util.Optional.empty();
        }
        java.util.List<String> candidates = application.getAllSources().stream()
                .filter(Class.class::isInstance)
                .map(source -> ((Class<?>) source).getSimpleName())
                .filter(StructuredLoggingDefaults::isApplicationClassName)
                .distinct()
                .toList();
        if (candidates.size() != 1) {
            return java.util.Optional.empty();
        }
        String simpleName = candidates.get(0);
        String base = simpleName.substring(0, simpleName.length() - APPLICATION_CLASS_SUFFIX.length());
        return java.util.Optional.of(toKebabCase(base));
    }

    /** {@code ...Application} 이고 접미사만으로 이뤄지지 않은 이름. */
    private static boolean isApplicationClassName(String simpleName) {
        return simpleName.endsWith(APPLICATION_CLASS_SUFFIX)
                && simpleName.length() > APPLICATION_CLASS_SUFFIX.length();
    }

    /** {@code StreamingApp} → {@code streaming-app}. 대문자 앞마다 하이픈을 넣고 소문자로 만든다. */
    private static String toKebabCase(String camelCase) {
        StringBuilder kebab = new StringBuilder(camelCase.length() + 4);
        for (int i = 0; i < camelCase.length(); i++) {
            char character = camelCase.charAt(i);
            if (Character.isUpperCase(character) && i > 0) {
                kebab.append('-');
            }
            kebab.append(Character.toLowerCase(character));
        }
        return kebab.toString().toLowerCase(Locale.ROOT);
    }
}
