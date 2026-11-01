package com.sapari.streamingapp.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sapari.streamingapp.websocket.ChatSessionRegistry;

import io.micrometer.core.instrument.FunctionCounter;
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
 * <p>⚠️ <b>다만 그 순서는 파드 용량 상한에만 적용된다.</b> 위 문단이 한때 "상한" 을 한 덩이로
 * 적었는데 축이 둘이다 — <b>용량</b>(파드가 몇 세션을 버티나)은 전송 계층을 재야 나오지만,
 * <b>유저당·방당 남용 상한</b>은 용량과 무관하다. 룸 토큰이 TTL 안에서 재사용 가능하므로 유효한
 * 토큰 한 장으로 한 파드에 세션을 계속 열 수 있고, 그걸 막는 값은 "정상 사용자가 동시에 몇 개를
 * 여는가"(탭·기기 수)에서 나와 부하 측정이 필요 없다. 이 게이지가 그 숫자를 대신 주지도 않는다 —
 * 파드 합계라서 유저당 분포가 보이지 않는다. 즉 남용 상한은 이 순서를 기다릴 이유가 없고, 실제로
 * 기다리지 않았다 — {@code ChatSessionRegistry.MAX_SESSIONS_PER_USER_PER_ROOM} 이 (유저, 방) 단위로
 * 집행하고, 아래 {@code chat.sessions.admission_capped} 가 그 집행을 센다. 남은 것은 용량 쪽뿐이다.
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
     * 동시 세션 상한으로 입장을 거부한 누적 수.
     *
     * <p>집행이 보이지 않으면 상한을 운영할 수 없다 — 이 수가 0 에서 움직이지 않으면 상한이 아무것도
     * 막지 않는 것이고, 꾸준히 오르면 공격이거나 <b>상한이 낮은</b> 것이다. 둘을 가르는 것은 사람이고,
     * 그 사람에게 줄 수 있는 유일한 수다.
     */
    static final String ADMISSION_CAPPED_COUNTER = "chat.sessions.admission_capped";

    /**
     * 게이지를 등록하고 그 사실을 빈으로 남긴다.
     *
     * <p>표식 빈을 돌려주는 이유는 등록이 <b>부수효과</b>라서다 — 반환값이 없으면 이 메서드가
     * 언젠가 "안 쓰이는 빈" 으로 보여 지워질 수 있고, 그러면 관측이 조용히 사라진다.
     *
     * <p>⚠️ <b>Micrometer 게이지는 대상을 weak reference 로 들고 있다</b>(바이트코드 확인: 게이지가
     * {@code WeakReference<T>} 를 들고 {@code value()} 가 {@code ref.get()} 이 {@code null} 이면
     * {@code NaN} 을 돌려준다). 여기서 안전한 이유는 넘기는 것이 {@code @Component} 싱글톤이라
     * 스프링이 강하게 들고 있기 때문이고 — <b>그 하나가 전부다</b>. 이 자리에서 어댑터나 임시 객체를
     * 만들어 넘기는 변경이 들어오면 게이지가 <b>조용히 NaN</b> 이 된다.
     *
     * <p>그 회귀는 이제 테스트가 잡는다 —
     * {@code ChatSessionGaugeConfigTest.theGaugeTargetOutlivesGarbageCollection} 이 컨텍스트에만
     * 들리게 둔 채 GC 를 밀고 값이 {@code NaN} 이 아닌지 본다. 다른 테스트들은 레지스트리를 필드로
     * 강하게 들고 있어서 이 회귀를 못 잡는다 — 그래서 그 하나만 컨텍스트로 띄운다(되돌림 확인:
     * 이 줄을 {@code Supplier} 어댑터로 바꾸면 그 테스트가 {@code actual: NaN} 으로 빨개진다).
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
        // 게이지가 아니라 FunctionCounter 다 — 누적 거부 수는 단조 증가라, 게이지로 내면 rate() 를
        // 쓸 수 없고 "지금 몇 개" 로 오독된다. 이 수는 증가율로 봐야 의미가 있다(상한이 방금 발화했나).
        FunctionCounter.builder(ADMISSION_CAPPED_COUNTER, registry, ChatSessionRegistry::admissionCapped)
                .description("동시 세션 상한으로 입장을 거부한 누적 수")
                .register(meters);
        return new SessionGauges(true);
    }

    /** 등록 여부를 들고 있는 표식 빈 — registry 가 없는 컨텍스트에서는 {@code false} 다. */
    public record SessionGauges(boolean registered) {
    }
}
