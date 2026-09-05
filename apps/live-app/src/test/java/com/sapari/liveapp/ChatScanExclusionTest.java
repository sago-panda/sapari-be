package com.sapari.liveapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Arrays;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * chat이 이 앱에 <b>스캔으로</b> 들어오지 못하게 막은 장치를 지킨다.
 *
 * <p>이 앱은 강퇴 REST 하나 때문에 chat-core를 갖는데, 그 모듈은 streaming-app과 공유돼 리액티브 어댑터가
 * {@code @Repository}를 달고 함께 산다. 그중 브로드캐스터는 생성자에서 Redis에 즉시 접속하고, 구독 핸들러는
 * 이 앱을 채팅 팬아웃 노드로 만든다. 필요한 블로킹 조각은 {@code ChatModerationBeansConfig}가 명시로 세운다.
 *
 * <p>여기서 막는 회귀는 조용하다 — 필터를 지워도 컴파일은 통과하고, 리액티브 빈이 붙은 채로 뜬 다음
 * 운영에서야 이상하게 동작한다. 컨텍스트를 띄우지 않고 애너테이션만 보므로 CI에서도 돈다.
 */
@DisplayName("chat 스캔 차단 — 지워지면 리액티브 어댑터가 함께 올라온다")
class ChatScanExclusionTest {

    /** 스캔에 걸리면 안 되는 대표 사례 — 생성자에서 Redis에 접속하는 브로드캐스터. */
    private static final String REACTIVE_ADAPTER =
            "com.sapari.chat.infrastructure.redis.RedisChatBroadcaster";

    private static Pattern chatExclusionPattern() {
        ComponentScan scan = LiveAppApplication.class.getAnnotation(ComponentScan.class);
        assertThat(scan).as("컴포넌트 스캔 설정이 사라졌다").isNotNull();
        return Arrays.stream(scan.excludeFilters())
                .filter(filter -> filter.type() == FilterType.REGEX)
                .flatMap(filter -> Arrays.stream(filter.pattern()))
                .map(Pattern::compile)
                .filter(pattern -> pattern.matcher(REACTIVE_ADAPTER).matches())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "chat 패키지를 빼는 정규식 필터가 없다 — 리액티브 어댑터가 스캔된다: " + REACTIVE_ADAPTER));
    }

    @Test
    @DisplayName("chat 패키지 전체가 스캔에서 빠진다 — 클래스 단위로 열거하면 새 어댑터를 놓친다")
    void chatPackageIsExcludedFromComponentScan() {
        // given
        Pattern pattern = chatExclusionPattern();

        // when & then: 아직 존재하지 않는 어댑터도 같은 패키지면 함께 막혀야 한다
        assertThat(pattern.matcher("com.sapari.chat.infrastructure.redis.SomeFutureAdapter").matches())
                .as("패키지가 아니라 특정 클래스만 막고 있다 — 어댑터가 늘면 조용히 새어 들어온다")
                .isTrue();
        assertThat(pattern.matcher("com.sapari.chat.application.handler.ChatBroadcastSubscriber").matches())
                .isTrue();
    }

    @Test
    @DisplayName("live 쪽은 계속 스캔된다 — 필터가 넓어지면 이 앱이 통째로 빈다")
    void liveClassesAreNotExcluded() {
        // given
        Pattern pattern = chatExclusionPattern();

        // when & then
        assertThat(pattern.matcher("com.sapari.live.application.service.EndLiveService").matches()).isFalse();
        assertThat(pattern.matcher("com.sapari.liveapp.controller.live.LiveController").matches()).isFalse();
    }

    @Test
    @DisplayName("리액티브 Mongo 자동설정 제외 이름이 실재한다 — 오타는 제외를 조용히 무력화한다")
    void everyExcludedNameResolvesToARealClass() {
        // given
        SpringBootApplication annotation =
                LiveAppApplication.class.getAnnotation(SpringBootApplication.class);

        // when & then
        assertThat(annotation.excludeName()).isNotEmpty();
        for (String name : annotation.excludeName()) {
            assertThatCode(() -> Class.forName(name))
                    .as("제외 대상 자동설정이 클래스패스에 없다 — 이름이 틀렸거나 의존이 사라졌다: %s", name)
                    .doesNotThrowAnyException();
        }
    }
}
