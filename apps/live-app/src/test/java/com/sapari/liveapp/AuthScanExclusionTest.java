package com.sapari.liveapp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import com.sapari.common.auth.RefreshTokenRedisRepository;

/**
 * {@code common/auth} 의 <b>읽는 구현만</b> 이 앱에 들어오게 막은 장치를 지킨다.
 *
 * <p>이 앱은 폐기 조회({@code TokenRevocationFilter}) 때문에 그 모듈을 갖는데, 거기에는 발급·갱신
 * 쪽 저장소도 함께 산다. 셋 다 {@code @Repository} 라 {@code basePackages="com.sapari"} 스캔이
 * 전부 집으므로 {@code LiveAppApplication} 이 발급 쪽 하나를 명시로 뺀다.
 *
 * <p><b>그 제외의 하중은 정합이 아니라 위생이다.</b> 빠뜨려도 부팅은 깨지지 않는다 —
 * {@code RefreshTokenRedisRepository} 의 의존이 {@code StringRedisTemplate}·{@code TimeProvider}
 * 둘뿐이고 이 앱에 둘 다 있다. 그래서 회귀가 조용하다: 쓰지 않는 저장소가 빈으로 뜨고,
 * 그때부터 "이 앱은 토큰을 발급·갱신하지 않는다" 는 그 파일의 문장이 빈 목록에서 거짓이 된다.
 *
 * <p><b>받는 쪽을 세는 칸이다.</b> 제외 목록은 클래스를 하나씩 적으므로, 그 모듈에 발급 쪽
 * {@code @Repository} 가 <b>네 번째로 생기면 아무것도 깨지지 않고</b> 목록만 조용히 낡는다.
 * 여기서 집합을 세어 그 순간 빨개지게 한다. 스프링 컨텍스트는 띄우지 않는다 —
 * {@code @ComponentScan} 이 실제로 쓰는 스캐너로 같은 패키지를 훑는다.
 */
@DisplayName("common/auth 스캔 — 발급 쪽이 늘면 제외 목록을 다시 보게 만든다")
class AuthScanExclusionTest {

    private static final String AUTH_PACKAGE = "com.sapari.common.auth";

    /** 이 앱의 설정 패키지 — 아래 "스테레오타입 하나만 보지 않는다" 를 재는 대조군이다. */
    private static final String LIVEAPP_CONFIG_PACKAGE = "com.sapari.liveapp.config";

    /** 오늘 그 패키지에 있는 전부. 늘면 이 칸이 깨지고, 그때 제외 목록을 판단한다. */
    private static final Set<String> KNOWN_COMPONENTS = Set.of(
            "AccessTokenBlacklistRedisRepository",   // 읽는 쪽 — 쓴다(jti)
            "SessionRevocationRedisRepository",      // 읽는 쪽 — 쓴다(sid)
            "RefreshTokenRedisRepository");          // 발급·갱신 쪽 — 스캔에서 뺀다

    /**
     * {@code @ComponentScan} 이 집는 것과 <b>같은 기준</b>으로 센다.
     *
     * <p>⚠️ 한때 이 필터가 {@code @Repository} 였다. 그러면 <b>가드의 분모가 가드 대상보다
     * 좁다</b> — 지키는 것은 {@code @ComponentScan} 이고 그쪽 기준은 {@code @Component} 라,
     * 그 패키지에 {@code @Service} 로 발급 쪽이 하나 생기면 스캔에는 들어오는데 이 칸은 초록으로
     * 남는다. {@code Component.class} 를 주면 세 스테레오타입이 모두 그 메타 애너테이션을
     * 달고 있으므로 함께 잡힌다 — 그 순회가 실제로 도는지는 아래 대조군 칸이 잰다.
     */
    private static Set<String> componentsIn(String basePackage) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        return scanner.findCandidateComponents(basePackage).stream()
                .map(definition -> definition.getBeanClassName())
                .map(name -> name.substring(name.lastIndexOf('.') + 1))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("⭐ 그 패키지의 스테레오타입 집합이 알려진 셋과 같다 — 네 번째가 생기면 제외를 판단해야 한다")
    void theAuthPackageHoldsExactlyTheKnownComponents() {
        assertThat(componentsIn(AUTH_PACKAGE))
                .as("늘었거나 줄었다 — LiveAppApplication 의 제외 목록과 그 주석을 함께 볼 것")
                .containsExactlyInAnyOrderElementsOf(KNOWN_COMPONENTS);
    }

    /**
     * ⭐ <b>위 칸이 {@code @Repository} 하나만 보는 것이 아님을 잰다.</b>
     *
     * <p>위 필터는 {@code Component.class} 인데, 그 패키지에는 오늘 {@code @Repository} 만 있다
     * (실측: {@code @Service}·{@code @Component}·{@code @Configuration} 0건). 그래서 <b>위 칸만으로는
     * 필터가 넓어졌는지 좁은 채인지 구분되지 않는다</b> — 두 경우의 결과가 같다. 메타 애너테이션
     * 순회가 실제로 도는지를 <b>{@code @Repository} 가 아닌 스테레오타입</b>으로 확인해야 뜻이 선다.
     *
     * <p>{@code @Configuration} 은 {@code @Component} 를 메타로 달고 있으므로, 같은 스캐너가
     * 이 앱의 설정 클래스를 집으면 그 순회가 돈 것이다. 즉 나중에 {@code @Service} 로 들어오는
     * 발급 쪽도 위 칸이 잡는다.
     */
    @Test
    @DisplayName("⭐ 같은 스캐너가 @Repository 아닌 스테레오타입도 집는다 — 분모가 @ComponentScan 과 같다")
    void theScannerFollowsMetaAnnotationsSoNonRepositoryStereotypesAreCountedToo() {
        assertThat(componentsIn(LIVEAPP_CONFIG_PACKAGE))
                .as("메타 순회가 안 돌면 위 칸의 필터가 사실상 @Component 직접 부착만 본다")
                .contains("EnterRateLimitConfig", "LiveSecurityConfig");
    }

    @Test
    @DisplayName("⭐ 발급 쪽은 스캔 제외에 들어 있고, 읽는 쪽 둘은 들어 있지 않다")
    void onlyTheIssuingRepositoryIsExcluded() {
        ComponentScan scan = LiveAppApplication.class.getAnnotation(ComponentScan.class);
        assertThat(scan).as("컴포넌트 스캔 설정이 사라졌다").isNotNull();

        Set<String> excluded = Arrays.stream(scan.excludeFilters())
                .filter(filter -> filter.type() == FilterType.ASSIGNABLE_TYPE)
                .flatMap(filter -> Arrays.stream(filter.classes()))
                .map(Class::getSimpleName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(excluded)
                .as("발급 쪽이 빠지면 쓰지 않는 refresh 저장소가 빈으로 뜬다")
                .contains(RefreshTokenRedisRepository.class.getSimpleName());
        assertThat(excluded)
                .as("읽는 쪽을 함께 빼면 폐기 조회 빈이 없어 부팅이 실패한다 — 필수 인자다")
                .doesNotContain("AccessTokenBlacklistRedisRepository")
                .doesNotContain("SessionRevocationRedisRepository");
    }
}
