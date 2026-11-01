package com.sapari.streamingapp.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sapari.streamingapp.websocket.ChatSessionRegistry;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 이 Pod 가 지금 들고 있는 <b>세션 수·방 수</b>를 게이지로 노출한다.
 *
 * <p><b>왜 지금 필요한가.</b> 이 앱에는 연결 수 상한이 없다 — 프레임 크기
 * ({@code maxFramePayloadLength}), 아웃바운드 버퍼(256), 느린 클라(1013 종료)는 모두 막혀 있는데
 * <b>연결 개수만</b> 무제한이다. 그 상한을 정하려면 "파드 하나가 몇 세션을 들고 있는가" 를 알아야
 * 하는데, 지금 그 수를 <b>어디에서도 볼 수 없었다</b>(기존 chat 지표는 전부 카운터다 — 끊긴 건수,
 * 드롭 건수, fail-open 건수). 그래서 상한값보다 이 게이지가 먼저다: 못 보는 값을 상한으로 고를 수는
 * 없다.
 *
 * <p>카운터가 아니라 <b>게이지</b>인 이유: 둘은 질문이 다르다. {@code chat.session.cut} 은 "몇 건을
 * 끊었나" 를 누적하지만 "지금 몇 개가 살아 있나" 에는 답하지 못한다. 상한·용량 판단에 필요한 쪽은
 * 후자이고, 그건 pull 이라 이벤트 포트({@code ChatMetrics})의 모양과 맞지 않는다 — 그래서 포트를
 * 넓히지 않고 여기서 레지스트리를 직접 읽는다.
 *
 * <p>방 수를 함께 내는 이유는 세션 수만으로는 모양을 못 읽기 때문이다. 2,000 세션이 방 하나에
 * 몰린 것과 방 200개에 퍼진 것은 팬아웃 비용이 전혀 다르고, 둘을 나눠야 "방당 세션" 이 나온다.
 *
 * <p><b>상한을 아직 고르지 않은 이유 — 재 보고 좁힌 결과다.</b> 앱 쪽 세션당 메모리를 실측했다
 * (세션 2만 개 등록 전후 heap, GC 뒤 측정):
 * <pre>
 * 세션당            2,349 bytes (2.29 KB)
 * 2,000 세션          4.5 MB
 * 12,000 세션        26.9 MB
 * </pre>
 * 즉 <b>앱 쪽은 제약이 아니다</b> — 아웃바운드 버퍼(256)·세션 레코드·sink 를 다 합쳐도 이 정도다.
 * 상한을 정하는 쪽은 전송 계층(Netty 채널 버퍼·TLS 세션)이고, <b>그건 이 저장소에서 잴 수 없다</b>
 * (여기 테스트는 목 세션이라 채널이 없다). 그래서 숫자는 실제 파드에 부하를 걸어야 나온다.
 *
 * <p>그때까지 할 수 있는 것이 이 게이지다. 순서가 이렇다 — <b>게이지(여기) → 임계 알림(인프라) →
 * 상한(숫자가 나온 뒤)</b>. 상한을 먼저 넣으면 둘 중 하나다: 값이 높아 아무것도 막지 못하거나,
 * 낮아서 인기 방송의 정상 시청자를 거부한다. 둘 다 "없는 것" 보다 나쁘다 — 후자는 장애를
 * <b>우리가 만든다</b>.
 *
 * <p>{@code ObjectProvider} 로 받는 이유는 {@code ChatMetricsConfig} 와 같다 —
 * {@code @ConditionalOnBean(MeterRegistry.class)} 은 사용자 {@code @Configuration} 이 자동 구성보다
 * 먼저 평가되는 탓에 registry 가 실제로는 있는데도 false 가 되는, 순서에 의존하는 함정이다. 그 실패는
 * 관측이 조용히 꺼지는 모양이라 알아채기 어렵다.
 */
@Configuration
public class ChatSessionGaugeConfig {

    /** 지금 이 Pod 의 세션 수. */
    static final String SESSIONS_GAUGE = "chat.sessions.local";

    /** 지금 이 Pod 가 중계 중인 방 수. */
    static final String ROOMS_GAUGE = "chat.rooms.local";

    /**
     * 게이지를 등록하고 그 사실을 빈으로 남긴다.
     *
     * <p>표식 빈을 돌려주는 이유는 등록이 <b>부수효과</b>라서다 — 반환값이 없으면 이 메서드가
     * 언젠가 "안 쓰이는 빈" 으로 보여 지워질 수 있고, 그러면 관측이 조용히 사라진다.
     */
    @Bean
    public SessionGauges chatSessionGauges(
            ChatSessionRegistry registry, ObjectProvider<MeterRegistry> meterRegistryProvider) {

        MeterRegistry meters = meterRegistryProvider.getIfAvailable();
        if (meters == null) {
            return new SessionGauges(false);
        }
        Gauge.builder(SESSIONS_GAUGE, registry, ChatSessionRegistry::localSessionCount)
                .description("이 Pod 가 지금 들고 있는 WebSocket 채팅 세션 수")
                .register(meters);
        Gauge.builder(ROOMS_GAUGE, registry, ChatSessionRegistry::localRoomCount)
                .description("이 Pod 가 지금 중계 중인 방 수")
                .register(meters);
        return new SessionGauges(true);
    }

    /** 등록 여부를 들고 있는 표식 빈 — registry 가 없는 컨텍스트에서는 {@code false} 다. */
    public record SessionGauges(boolean registered) {
    }
}
