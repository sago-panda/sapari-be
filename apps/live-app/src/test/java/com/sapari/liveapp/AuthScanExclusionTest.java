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
import org.springframework.stereotype.Repository;

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

    /** 오늘 그 패키지에 있는 전부. 늘면 이 칸이 깨지고, 그때 제외 목록을 판단한다. */
    private static final Set<String> KNOWN_REPOSITORIES = Set.of(
            "AccessTokenBlacklistRedisRepository",   // 읽는 쪽 — 쓴다(jti)
            "SessionRevocationRedisRepository",      // 읽는 쪽 — 쓴다(sid)
            "RefreshTokenRedisRepository");          // 발급·갱신 쪽 — 스캔에서 뺀다

    private static Set<String> repositoriesInAuthPackage() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Repository.class));
        return scanner.findCandidateComponents(AUTH_PACKAGE).stream()
                .map(definition -> definition.getBeanClassName())
                .map(name -> name.substring(name.lastIndexOf('.') + 1))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("⭐ 그 패키지의 @Repository 집합이 알려진 셋과 같다 — 네 번째가 생기면 제외를 판단해야 한다")
    void theAuthPackageHoldsExactlyTheKnownRepositories() {
        assertThat(repositoriesInAuthPackage())
                .as("늘었거나 줄었다 — LiveAppApplication 의 제외 목록과 그 주석을 함께 볼 것")
                .containsExactlyInAnyOrderElementsOf(KNOWN_REPOSITORIES);
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
