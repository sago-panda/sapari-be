package com.sapari.streamingapp.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.domain.repository.ChatSessionRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;

import reactor.core.publisher.Mono;

/**
 * (유저, 방) 상한이 <b>운영 설정에서</b> 레지스트리까지 도달한다.
 *
 * <p>집행 자체는 {@code SessionAdmissionCapTest} 가 잰다. 여기서 재는 것은 그 앞 단계다 —
 * 값이 코드 상수가 아니라 <b>Spring 이 고른 생성자</b>를 타고 들어오는지. 둘을 가르는 이유는
 * 집행 칸이 생성자를 직접 부르기 때문이다: 생성자에 {@code @Value} 가 안 붙어 있어도 그 칸은
 * 전부 초록이고, 운영에서는 프로퍼티가 무시된다.
 *
 * <p>⚠️ <b>미설정 칸이 같이 있어야 한다.</b> {@code @Value} 의 기본값 리터럴과
 * {@code DEFAULT_MAX_SESSIONS_PER_USER_PER_ROOM} 은 <b>같은 수의 두 벌</b>이다(형제
 * {@code WebSocketConfig} 가 그 갈림을 반복해 잡았다고 적어 둔 그 모양이다). 설정 칸만 두면
 * 두 벌이 갈려도 초록이므로, "미설정이면 코드 상수와 같다" 를 행동으로 고정한다. 리터럴끼리의
 * 일치는 {@code ConfigTemplateTest} 가 템플릿까지 묶어 따로 잰다.
 */
@DisplayName("세션 상한 배선 — 설정값이 레지스트리까지 간다")
class SessionCapWiringTest {

    private ApplicationContextRunner runner() {
        ChatSessionRepository repository = mock(ChatSessionRepository.class);
        // @PostConstruct 의 재등록 타이머가 이 값을 읽는다 — 스텁이 없으면 컨텍스트가 NPE 로 뜨지 않는다.
        lenient().when(repository.heartbeatInterval()).thenReturn(Duration.ofMinutes(10));
        lenient().when(repository.add(any(), any(), any())).thenReturn(Mono.empty());
        lenient().when(repository.remove(any(), any())).thenReturn(Mono.empty());

        // ⚠️ placeholder 해석용 자동구성을 넣지 않는다. 한때 여기 그걸 넣고 "없으면 ${...} 가
        // 날것으로 남는다" 고 적었는데 **안 재고 쓴 문장이었다** — 실측: 그 줄을 지워도 이 파일
        // 다섯 칸이 전부 초록이다(형제 ChatSessionGaugeConfigTest:140 도 같은 빈을 그것 없이 띄운다).
        return new ApplicationContextRunner()
                .withBean(ChatSessionRepository.class, () -> repository)
                .withBean(ChatPermissionPolicy.class, ChatPermissionPolicy::new)
                .withBean(ChatMetrics.class, () -> ChatMetrics.NOOP)
                .withBean(ChatSessionRegistry.class);
    }

    @Test
    @DisplayName("⭐ 설정한 값이 레지스트리에 들어간다 — 기본과 다른 수를 써서 '우연히 같음'을 지운다")
    void theConfiguredValueReachesTheRegistry() {
        runner().withPropertyValues("chat.session.max-per-room=3").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ChatSessionRegistry.class).maxSessionsPerUserPerRoom())
                    .as("프로퍼티가 무시되면 운영에서 상한을 바꿀 수 없고, 지표가 가리키는 조치가 불가능해진다")
                    .isEqualTo(3);
        });
    }

    @Test
    @DisplayName("⭐ 상한이 1 미만이면 기동을 실패시킨다 — 조용히 '상한 없음'으로 뜨면 오설정이 안 드러난다")
    void aCapBelowOneFailsStartup() {
        // 0 과 음수를 따로 본다: `< 1` 대신 `== 0` 으로 적는 회귀가 한 값만 보는 칸으로는 안 드러난다.
        for (String bad : new String[] {"chat.session.max-per-room=0", "chat.session.max-per-room=-1"}) {
            runner().withPropertyValues(bad).run(context -> assertThat(context)
                    .as("%s 로도 떴다 — 오설정이 상한 없이 도는 파드가 된다", bad)
                    .hasFailed());
        }
    }

    @Test
    @DisplayName("⭐ 거부 사유가 프로퍼티 이름을 말한다 — 기동 로그만 보고 어느 키인지 알 수 있게")
    void theStartupFailureNamesTheProperty() {
        // ⚠️ 최상위 메시지가 아니라 **스택 전체**를 본다. 실측: 최상위는 Spring 의 래퍼
        // ("Error creating bean with name 'chatSessionRegistry': ... Constructor threw exception")
        // 이고 키 이름은 cause 에 있다. 운영자가 기동 로그에서 읽는 것은 cause 체인까지이므로
        // 그 축으로 단언한다 — 최상위만 보면 "이름을 말한다" 가 통과할 수 없고, 메시지를 래퍼에
        // 맞춰 고치면 정작 쓸모 있는 자리를 비우게 된다.
        runner().withPropertyValues("chat.session.max-per-room=0").run(context -> assertThat(context)
                .getFailure()
                .hasStackTraceContaining("chat.session.max-per-room"));
    }

    @Test
    @DisplayName("상한 1 은 받는다 — 하한을 한 칸 안쪽으로 잡는 회귀를 막는다(항상 거부가 아니다)")
    void aCapOfOneIsAccepted() {
        runner().withPropertyValues("chat.session.max-per-room=1").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ChatSessionRegistry.class).maxSessionsPerUserPerRoom()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("⭐ 미설정이면 코드 기본값이다 — @Value 기본값과 상수가 갈리는 것을 행동으로 막는다")
    void theCodeDefaultAppliesWhenUnset() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ChatSessionRegistry.class).maxSessionsPerUserPerRoom())
                    .isEqualTo(ChatSessionRegistry.DEFAULT_MAX_SESSIONS_PER_USER_PER_ROOM);
        });
    }
}
