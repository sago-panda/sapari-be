package com.sapari.streamingapp.websocket;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.port.DeliveryStage;
import com.sapari.chat.application.port.SessionCutReason;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatSessionRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;

import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

/**
 * {@link ChatSessionManager} 구현 — 이 Pod에 붙은 WS 세션의 로컬 명부 + Redis HASH(크로스 Pod 집계) 조율.
 *
 * <p><b>왜 transport(streaming-app)에 두나</b>: 실제 송신 채널을 쥐는 유일한 곳이라서. chat-core 포트는
 * reactor Sink·WS 채널을 모르고(레이어 누수 방지) 도메인 {@link ChatSession}만 다룬다. 그래서 채널(Sink)은
 * 이 구현체가 소유하고, 핸들러는 {@link #outboundFrames(String)}로 아웃바운드 스트림을 받아
 * session.send()에 연결한다({@code outbound(String)} 은 같은 스트림을 메시지로만 본 package-private
 * 뷰다 — 공개 구독 진입점은 하나다).
 *
 * <p><b>Sink</b>: 외부(이 레지스트리)에서 임의 시점에 값을 밀어넣는 통로. T10 구독자가 메시지를 받으면
 * {@code sendToSession}으로 해당 세션 Sink에 emit → 그 WS로 흘러나간다. unicast+버퍼라 구독(=session.send) 전
 * emit도 버퍼에 보관된다.
 */
@Slf4j
@Component
public class ChatSessionRegistry implements ChatSessionManager {

    /** 세션당 아웃바운드 버퍼 상한. 방 하나가 초당 수십 건 × 몇 초 적체를 견디는 크기. */
    static final int OUTBOUND_BUFFER_SIZE = 256;

    /**
     * 거부 응답을 되돌려주는 최소 간격.
     *
     * <p><b>세지 않고 솎아낸다.</b> 거부 프레임을 누적해 세다 상한에서 끊는 방식을 먼저 시도했는데,
     * 무엇을 셀지 정하는 순간 <b>세지 않는 사유가 곧 우회로</b>가 됐다(파싱 실패만 세면 {@code {}}가,
     * 검증 실패만 세면 레이트리밋 창이 비켜갔다). 게다가 누적이라 200자 초과처럼 <b>정상 사용자가
     * 반복하는 실수</b>도 결국 상한에 닿아 연결을 잃게 만들었다.
     *
     * <p>솎아내면 그 둘이 같이 사라진다 — 사유를 가리지 않으므로 우회할 문이 없고, 연결을 끊지 않으므로
     * 오탐도 없다. 막으려던 것은 애초에 되돌림 비용이었고, 답을 안 보내면 그 비용이 0이다.
     * 첫 거부는 반드시 답한다 — 클라가 무엇이 틀렸는지 알아야 고친다.
     */
    private static final long REJECTION_REPLY_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();

    /**
     * 세션당 인바운드 프레임 상한 — 간격 하나에 한 프레임, 연달아서는 {@link #INBOUND_FRAME_BURST} 까지.
     *
     * <p><b>막으려는 것은 이벤트 루프에서 드는 프레임당 비용이다</b> — UTF-8 디코드와 JSON 파싱. 레이트리밋과
     * 거부 응답 솎기는 둘 다 그 뒤에 있어서, 이것이 없으면 회선 속도로 미는 클라 하나가 프레임마다 그 비용을
     * 이벤트 루프에 얹는다. 4KiB 프레임의 파싱은 모양에 따라 2.5µs(정상)~약 29µs(모르는 키의 값이 중첩 객체,
     * 파싱은 성공)였고, 디코드는 한글 4KiB 에 약 2.3µs 였다(실측: 운영 ObjectMapper 설정, openjdk 21, 15배치
     * 중앙값). 상한은 디코드 앞에 있으므로(ChatWebSocketHandler.runInbound) 받아들인 프레임의 지속 비용은
     * 최악 모양으로 초당 약 0.31ms((29 + 2.3)µs × 10)이고, 버린 프레임에는 맵 조회 하나만 남는다.
     * 이 상한이 묶지 않는 것은 그 앞의 수신·프레이밍이다 — 그쪽은 프레임 크기 상한과 회선이 정한다.
     *
     * <p><b>정상 클라는 닿지 않는다.</b> 회원은 레이트리밋이 평균 3초에 한 건(연속 3건)이고, 레이트리밋이 면제되는 방주인도
     * 사람이 치는 속도다. 이 값은 정상 사용량을 재서 정한 것이 아니라 그보다 넉넉히 둔 것이다.
     */
    static final long INBOUND_FRAME_INTERVAL_NANOS = Duration.ofMillis(100).toNanos();

    /** 쉬다가 연달아 받을 수 있는 프레임 수. */
    static final int INBOUND_FRAME_BURST = 20;

    /**
     * 동시 emit 경합 재시도 상한(벽시계 아님 — 스핀 시간 측정이라 TimeProvider 대상이 아니다).
     *
     * <p>락 보유 구간은 큐 적재 + 드레인(직렬화)이라 정상 상태엔 수십 µs 수준이고 5ms는 두 자릿수 여유다.
     * 여기 닿는 건 경합이 길어서가 아니라 <b>보유 스레드가 느려지거나 선점될 때</b>다 — 기동 직후 첫 드레인,
     * STW GC, CPU 스로틀링. 셋 다 JVM 전역이라 여러 세션이 동시에 걸린다. 그래서 상한을 짧게 두어
     * 이벤트루프 점유를 줄이되, 소진했다고 세션을 끊지는 않는다(emit 주석 참고).
     *
     * <p>⚠️ <b>그 여유는 emit 한 건 기준이고, 이 값은 emit마다 주어지지 않는다.</b> 방 fan-out은 한
     * 연산이 예산 하나를 나눠 쓰므로(deadline은 절대 시각이다 — {@code emitSerially} 참고) 실질 여유가
     * 방 세션 수에 비례해 줄어든다. 실측으로 60세션 fan-out 1회가 0.100ms(이 값의 2%)인데 12,000세션은
     * 3.96ms(79%)다. 이 값을 읽고 "두 자릿수 여유가 있으니 예산 소비자를 더 붙여도 된다"로 가면,
     * 큰 방에서 꼬리 세션부터 재시도 없이 한 번만 시도하게 된다.
     */
    private static final long DEFAULT_CONTENTION_SPIN_NANOS = Duration.ofMillis(5).toNanos();

    /**
     * 종료 신호 재시도 상한. emit보다 넉넉히 주는 이유는 종료 보장이 아니라 <b>전달 확률</b>이다 —
     * 이 신호가 걸려야 버퍼 잔여분이 흘러나가고, 강퇴 직전에 보낸 SYSTEM(KICKED)이 클라에 닿는다.
     * 놓쳐도 제어 채널이 종료 자체는 책임진다({@code terminate} 참고).
     */
    private static final long DEFAULT_COMPLETE_SPIN_NANOS = Duration.ofMillis(50).toNanos();

    /**
     * 드롭 로그 솎아내기 간격 — 드롭이 몰리는 상황이 곧 CPU 포화라 건당 로그가 상황을 악화시킨다.
     * <b>건수가 아니라 경과시간</b>으로 센다: 건수 기준은 카운터가 프로세스 생애 누적이라 두 번째 이후의
     * 짧은 버스트가 통째로 침묵한다 — 드롭은 실제 메시지 유실이라 "조용한 유실이 되지 않게"라는
     * 이 클래스의 목표와 정면으로 어긋난다.
     */
    private static final long DROP_LOG_INTERVAL_NANOS = Duration.ofSeconds(10).toNanos();

    private final ChatSessionRepository sessionRepository;   // Redis HASH 어댑터(T6) — 크로스 Pod activeCount

    /**
     * 관측 포트. 아래 카운터들은 프로세스 안에만 있었다 — 드롭은 "실시간 전달 best-effort" 계약이
     * 실제로 발화한 지점이고, 종료 사유는 <b>와이어에서 구분되지 않는다</b>(방 종료·수명·생존 실패가
     * 전부 close 1000). 밖에서 보면 "정상 종료가 늘었다" 로만 보이는 것을 여기서만 가를 수 있다.
     */
    private final ChatMetrics metrics;

    /**
     * 감사 판정을 손으로 적지 않기 위해 받는다.
     *
     * <p>"특권 뷰를 쓰는가"는 팬아웃도 묻는 질문이라, 두 곳에 각자 적으면 특권 뷰를 받는 역할이 늘 때
     * 한쪽만 따라간다. 뒤처지는 쪽이 감사이고, 그러면 노출은 늘고 흔적은 주는 방향으로 갈린다.
     */
    private final ChatPermissionPolicy permissionPolicy;

    /**
     * emit 경합 재시도 상한. 운영은 {@link #DEFAULT_CONTENTION_SPIN_NANOS}.
     *
     * <p>상수가 아니라 필드인 이유는 <b>테스트가 이 값을 넓혀야 하기 때문</b>이다. 이 값에 기대는
     * 테스트들이 고정하는 것은 절대값이 아니라 <b>관계</b>다 — "예산보다 짧은 경합은 넘겨진다",
     * "예산보다 긴 경합은 버려지고 계수된다". 상수로 두면 그 관계를 세울 유일한 레버가 체류 시간이고,
     * 그러면 마진이 ms 자릿수에 갇힌다(1ms 체류 vs 5ms 예산 = 5배). 스레드가 수십 ms 선점되는 것은
     * 부하에서 흔한 일이라 그 마진은 지워지고, 테스트는 <b>설계가 약속하지 않은 것</b>을 요구하다
     * 흔들린다(경합 소진 시 드롭은 best-effort 계약이다).
     *
     * <p>예산과 체류를 <b>같이</b> 키우면 관계가 글자 그대로 보존되면서 마진만 곱해진다. 기본값에서
     * 동작은 완전히 동일하다 — 행동 변화가 0 인 진입점이다.
     *
     * <p>⚠️ 결정론은 아니다. 벽시계는 여전히 벽시계이고, 필요한 선점이 ms 에서 수백 ms 로 옮겨가
     * 확률이 내려가는 것이다. 시계를 가짜로 바꾸는 쪽은 안 된다 — {@code Thread.onSpinWait()} 루프가
     * 가짜 시계로는 돌지 않아 재려던 기제 자체가 사라진다. <b>키우되 가짜로 만들지 않는다.</b>
     */
    private final long contentionSpinNanos;

    /** 종료 신호 재시도 상한. 운영은 {@link #DEFAULT_COMPLETE_SPIN_NANOS}. 위와 같은 이유로 필드다. */
    private final long completeSpinNanos;

    /**
     * 운영 생성자. 상한만 외부에서 받는다 — 나머지 예산은 운영값이 하나뿐이라 상수로 둔다.
     *
     * <p>⚠️ <b>Spring 이 고르는 생성자는 이것이다.</b> 아래 좁은 생성자들은 기본 상한으로 위임하는
     * 테스트 진입점이고, 그래서 "운영이 무엇을 받는가" 를 묻는 칸은 반드시 <b>이</b> 생성자를 불러야
     * 한다({@code ChatSessionRegistryTest.production_constructor_uses_the_production_budgets}).
     *
     * <p><b>{@code @Value} 를 여기 직접 두는 이유.</b> 형제 {@code WebSocketConfig} 는 수명 셋을
     * 빈으로 모아 두고 그 이유를 "각자 {@code @Value} 로 받으면 기본값이 두 벌이 된다" 로 적었다 —
     * 그 값은 <b>소비자가 둘</b>(핸들러와 {@code RoomTokenVerifier})이기 때문이다. 상한은 소비자가
     * 이 클래스 하나뿐이라 그 중복이 생기지 않는다.
     *
     * <p><b>기본값은 한 벌이다.</b> 플레이스홀더의 기본값을
     * {@link #DEFAULT_MAX_SESSIONS_PER_USER_PER_ROOM} 연결로 적는다 — 문자열 리터럴과
     * {@code static final int} 의 연결은 자바 <b>상수식</b>이라 어노테이션 값으로 적법하다(실측).
     * 그래서 숫자를 두 자리에 적고 기계로 묶을 필요가 없다. 템플릿 주석과의 일치는 별개 면이라
     * {@code ConfigTemplateTest} 가 따로 잰다.
     */
    @Autowired
    public ChatSessionRegistry(ChatSessionRepository sessionRepository,
                               ChatPermissionPolicy permissionPolicy,
                               ChatMetrics metrics,
                               @Value("${chat.session.max-per-room:" + DEFAULT_MAX_SESSIONS_PER_USER_PER_ROOM + "}") int maxSessionsPerUserPerRoom) {
        this(sessionRepository, permissionPolicy, metrics, maxSessionsPerUserPerRoom,
                DEFAULT_CONTENTION_SPIN_NANOS, DEFAULT_COMPLETE_SPIN_NANOS);
    }

    /** 기본 상한으로 위임하는 테스트 진입점 — 상한이 축이 아닌 칸들이 쓴다. */
    ChatSessionRegistry(ChatSessionRepository sessionRepository,
                        ChatPermissionPolicy permissionPolicy,
                        ChatMetrics metrics) {
        this(sessionRepository, permissionPolicy, metrics, DEFAULT_MAX_SESSIONS_PER_USER_PER_ROOM,
                DEFAULT_CONTENTION_SPIN_NANOS, DEFAULT_COMPLETE_SPIN_NANOS);
    }

    /** 운영 생성자가 실제로 운영 예산을 넘기는지 재기 위한 진입점 — 넓힌 예산이 테스트에만 머물게 한다. */
    long contentionSpinNanos() {
        return contentionSpinNanos;
    }

    /** 위와 같다. 둘을 따로 읽는 이유는 뒤바꿔 넘기는 실수가 한 값만 보면 드러나지 않기 때문이다. */
    long completeSpinNanos() {
        return completeSpinNanos;
    }

    /** 예산을 넓혀 받는다 (테스트 진입점 — 위 {@link #contentionSpinNanos} 참고). */
    ChatSessionRegistry(ChatSessionRepository sessionRepository, ChatPermissionPolicy permissionPolicy,
                        ChatMetrics metrics, long contentionSpinNanos, long completeSpinNanos) {
        this(sessionRepository, permissionPolicy, metrics, DEFAULT_MAX_SESSIONS_PER_USER_PER_ROOM,
                contentionSpinNanos, completeSpinNanos);
    }

    /**
     * 정규 생성자 — 위 셋이 모두 여기로 모인다.
     *
     * <p>상한이 1 미만이면 <b>기동을 실패시킨다.</b> 조용히 기본값으로 떠서는 안 되는 이유는 0 과
     * "상한 없음" 이 운영에서 같은 모양이기 때문이다 — 오타 하나로 상한 없이 도는 파드가 생기고,
     * 그 파드만 토큰 재사용을 못 막는다. 저장소가 같은 자리에서 고른 쪽이기도 하다
     * ({@code LiveSecurityConfig.managementPortMustDiffer}, {@code ShedLockTableGuard}).
     *
     * <p><b>상한은 두지 않는다.</b> 아주 큰 값은 "상한 없음" 이고 그건 운영이 고를 수 있는 상태다
     * (SPR-152 전의 상태가 그것이다). 문서의 "2× 상한" 유도는 상한에 비례하므로 값이 커져도 참이다.
     *
     * <p>크기에 매달린 경로가 하나 있는데 <b>방향이 직관과 반대다</b>(한때 이 자리에 "큰 값이 조용히
     * 깨뜨리는 불변식도 없다" 고 안 재고 적었고, 그 다음엔 "상한만큼 훑는다" 로 반대로 적었다).
     * {@link #reapUnresponsiveIfFull} 은 <b>점유가 상한에 닿았을 때만</b> 돈다 —
     * {@code size() < 상한} 에서 즉시 돌아오기 때문이다. 닿으면 {@code computeIfPresent} 안에서
     * 점유 집합을 전부 훑고, 그 스캔은 <b>상한이 아니라 점유 크기</b>에 비례한다. 그래서
     * <b>상한을 올리면 이 경로는 덜 돈다</b>(닿기가 어려워진다). 상한만큼의 스캔은 한 (유저, 방)이
     * 실제로 그만큼의 <b>산</b> 세션을 들고 있을 때만 생기고, 그 상태를 떠받치는 비용(싱크·버퍼·팬아웃)이
     * 스캔보다 크다. 절대 비용은 이쪽에서 재지 않았다.
     */
    private ChatSessionRegistry(ChatSessionRepository sessionRepository, ChatPermissionPolicy permissionPolicy,
                                ChatMetrics metrics, int maxSessionsPerUserPerRoom,
                                long contentionSpinNanos, long completeSpinNanos) {
        if (maxSessionsPerUserPerRoom < 1) {
            // 프로퍼티 이름을 메시지에 싣는다 — 기동 로그만 보고 어느 키를 고칠지 알 수 있어야 한다.
            throw new IllegalArgumentException(
                    "chat.session.max-per-room 은 1 이상이어야 합니다: " + maxSessionsPerUserPerRoom);
        }
        this.sessionRepository = sessionRepository;
        this.permissionPolicy = permissionPolicy;
        this.metrics = metrics;
        this.maxSessionsPerUserPerRoom = maxSessionsPerUserPerRoom;
        this.contentionSpinNanos = contentionSpinNanos;
        this.completeSpinNanos = completeSpinNanos;
    }

    /**
     * 이 파드가 집행하는 (유저, 방) 상한. 운영 설정으로 바뀐다 — 기본은
     * {@link #DEFAULT_MAX_SESSIONS_PER_USER_PER_ROOM}.
     *
     * <p>{@code public} 인 이유는 다른 패키지의 게이지 설정이 읽기 때문이다
     * ({@code ChatSessionGaugeConfig} → {@code chat.sessions.max_per_room}). 같은 자리의
     * {@code localSessionCount}·{@code localRoomCount}·{@code admissionCapped}·{@code slotsReaped}
     * 넷이 모두 같은 이유로 {@code public} 이다.
     */
    public int maxSessionsPerUserPerRoom() {
        return maxSessionsPerUserPerRoom;
    }

    /** sessionId → (도메인 세션 + 아웃바운드 Sink). 로컬 메모리(이 Pod 한정). */
    private final Map<String, LocalSession> local = new ConcurrentHashMap<>();

    /** Pod 전체의 재등록 타이머 — 세션당 하나가 아니다. 종료 시 해제한다. */
    // volatile — @PostConstruct 가 쓰고 @PreDestroy 가 읽는데 둘이 같은 스레드라는 보장이 계약에
    // 없다. 비용이 0 이라 의심을 남기지 않는 쪽으로 둔다.
    private volatile Disposable heartbeat;

    /** 재등록 실패 누적 — 전 세션의 재등록이 조용히 멈춘 상태를 로그 한 줄로만 남기지 않기 위한 값. */
    private final AtomicLong heartbeatFailures = new AtomicLong();

    /**
     * roomId → 이 Pod 가 마지막으로 보낸 시청자 수.
     *
     * <p>변했을 때만 보내기 위한 상태다. 매 회차 뿌리면 큰 방에서는 그 비용이 전부고(세션 수만큼의
     * 프레임), 조용한 방에서도 쉬지 않는다. 방이 비면 {@code unregister} 가 같이 걷어낸다 —
     * 남기면 그쪽이 누수원이 된다({@code activeCountCache} 와 같은 규율).
     */
    private final Map<UUID, Long> lastPushedCount = new ConcurrentHashMap<>();

    /**
     * Pod 안의 모든 로컬 세션을 주기적으로 <b>재등록</b>한다.
     *
     * <p>이게 없으면 세션 명부가 "마지막 입장 이후" 기준으로만 갱신돼, Pod 가 크래시한 뒤 남은 항목이
     * 키 TTL(24시간)까지 시청자 수를 부풀린다. 그리고 한산한 방은 신규 입장이 없어 방송 중에 키가
     * 만료되기도 했다 — 둘 다 방송 내내 틀린 수가 보이는 모양이다.
     *
     * <p><b>세션당 타이머가 아니라 Pod 당 하나다.</b> 세션당으로 두면 수천 개 타이머가 생기고, 이미
     * 세션마다 생존 확인 인터벌이 하나 있어 그걸 두 배로 만든다. 레지스트리는 로컬 세션 전부를 알고
     * 있으므로 한 타이머가 전부 돌면 된다.
     *
     * <p>주기는 <b>포트가 정한다</b>({@code heartbeatInterval}) — "얼마나 자주 재등록해야 집계에
     * 남는가" 는 그 어댑터의 계약이고, 여기서 따로 정하면 한쪽을 바꿀 때 다른 쪽이 따라가지 않는다.
     *
     * <p>실패는 <b>그 세션만</b> 흡수한다. 한 세션의 재등록 실패가 스트림을 끝내면 그 뒤로 이 Pod 의
     * 재등록이 통째로 멈추고, 그러면 살아 있는 시청자 전원이 상한 뒤에 집계에서 사라진다 — 고치려던
     * 것보다 나쁘다. 다음 주기가 받아 주므로 건당 실패는 유실이 아니다.
     *
     * <p>{@code concatMap} 이다 — 한 주기가 끝나기 전에 다음 주기가 겹쳐 들어오면 Redis 쓰기가 두 배로
     * 간다. 느린 Redis 가 그 모양을 만들고, 그때 더 쓰는 것이 가장 나쁜 대응이다.
     *
     * <p>그래서 밀린 tick 은 <b>버린다</b>({@code onBackpressureDrop}). 이 드롭이 보장하는 것은
     * <b>동시 쓰기 상한</b>이다 — 어느 순간에도 이 Pod 의 재등록 회차는 하나다. 낡음 상한을 보장하지는
     * <b>않는다</b>.
     *
     * <p>⚠️ <b>실측</b>(주기 20ms, 2초 = 약 100 tick):
     * <pre>
     * 안쪽이 안 끝난다(멈춘 Redis)      통과 1   드롭 98
     * 안쪽이 주기의 5배로 느리다        통과 17  드롭 83
     * </pre>
     * 실효 재등록 주기는 설정값이 아니라 <b>한 회차가 걸리는 시간</b>이 된다. 그래서 "상한이 주기의
     * 네 배라 연속 세 회차를 잃어도 된다" 는 <b>한 회차가 한 주기 안에 끝날 때만</b> 성립하고, 그걸
     * 지키는 장치는 없다. 안쪽이 주기의 다섯 배로 느려지면 실효 주기가 상한을 넘어 <b>살아 있는 세션이
     * 집계에서 빠진다</b> — 이 드롭이 막으려던 실패와 같은 것이다. 더 나쁜 쪽은 안쪽이 아예 안 끝나는
     * 경우로, 그때는 재등록이 영구히 멈추는데 {@code heartbeatFailures} 는 0 이다(실패가 아니라
     * 미완료라서). 회차 길이를 재는 신호는 아직 없다 — 별건으로 남긴다.
     *
     * <p>⚠️ 어댑터의 정적 가드가 지키는 하한은 <b>3배</b>다(4배가 아니다) — 지금 값이 4배지만 3배로
     * 줄여도 가드는 통과한다. 즉 네 배는 현재 값의 성질이고 가드가 보장하는 값이 아니다.
     */
    @PostConstruct
    void startHeartbeat() {
        Duration interval = sessionRepository.heartbeatInterval();
        heartbeat = Flux.interval(interval, interval)
                .onBackpressureDrop(tick -> { })
                .concatMap(tick -> Flux.fromIterable(local.values())
                        .flatMap(this::reregister)
                        .then()
                        // 같은 타이머에 시청자 수 갱신을 얹는다 — 둘 다 "이 Pod 가 주기마다 하는 일"
                        // 이고 타이머를 하나 더 두면 세션 수만큼의 비용이 두 배가 된다. 순서가 이쪽인
                        // 이유는 재등록이 먼저 끝나야 그 회차의 수가 좀비를 걷어낸 값이기 때문이다.
                        .then(pushViewerCounts())
                        // ⭐ 회차 단위로 흡수한다. 여기까지 올라온 onError 는 concatMap 을 타고
                        // 밖으로 나가 subscribe() 를 끝내는데, 그러면 이 Pod 는 재시작 전까지
                        // 재등록과 시청자 수 갱신을 둘 다 잃는다 — 살아 있는 시청자 전원이 낡음
                        // 상한 뒤에 집계에서 사라지고, 아무 신호가 없다.
                        //
                        // 오늘은 여기 도달하지 않는다: 잎 셋이 전부 흡수한다(reregister 의
                        // onErrorResume, pushViewerCount 의 onErrorResume, 지표의 safe()). 그래도
                        // 두는 이유는 그 불변식이 잎 하나를 추가하는 변경으로 조용히 깨지고, 깨진
                        // 결과가 "이 Pod 의 관측이 통째로 멈춤" 이라는 비대칭 때문이다.
                        // ChatAccountEventHandler·LiveRoomEndedHandler 가 같은 모양을 같은 근거로
                        // 이미 막아 두었고, 여기만 비어 있었다.
                        .onErrorResume(e -> {
                            log.warn("하트비트 회차가 실패했다 — 다음 주기가 받는다", e);
                            return Mono.<Void>empty();
                        }))
                .subscribe();
    }

    @PreDestroy
    void stopHeartbeat() {
        if (heartbeat != null) {
            heartbeat.dispose();
        }
    }

    /**
     * 한 세션의 재등록. 실패는 흡수하고 규모만 남긴다(테스트 진입점).
     *
     * <p><b>쓰기 직전에 로컬 명부를 다시 본다.</b> 재등록은 TTL touch 가 아니라 등재와 같은 upsert 라
     * (그게 Redis 데이터 유실 뒤 복구를 가능하게 하는 선택이다), 이미 끊긴 세션에 늦게 착지하면
     * {@code unregister} 의 HDEL 이 지운 필드를 <b>되살린다</b>. 되살아난 필드는 그 순간의 시각을
     * 달고 있어 살아 있는 것으로 집계되고, 더 갱신되지 않으므로 상한만큼 지나 빠진다 — 그동안 시청자
     * 수가 하나 많다. 방 종료의 {@code clearRoom} 뒤에 착지하면 키 자체를 TTL 과 함께 되살린다.
     *
     * <p>이 확인이 창을 <b>없애지는 못한다</b>(확인과 쓰기 사이는 여전히 열려 있다). 다만 창을 "한
     * 회차 전체" 에서 "Redis 왕복 한 번" 으로 줄인다 — 세션이 많으면 그 차이가 수 초 대 수 밀리초다.
     * 완전히 닫으려면 세션별 직렬화가 필요하고, 유계·자가치유인 과다계수에 그 값은 과하다.
     */
    Mono<Void> reregister(LocalSession ls) {
        return Mono.defer(() -> {
                    if (!local.containsKey(ls.sessionId())) {
                        return Mono.<Void>empty();
                    }
                    return sessionRepository.heartbeat(
                            ls.session().roomId(), ls.sessionId(), ls.session().userId());
                })
                .onErrorResume(e -> {
                    long failures = heartbeatFailures.incrementAndGet();
                    if (shouldLogReregisterFail()) {
                        log.warn("세션 명부 재등록 실패 — 다음 주기가 받는다(누적 {}건) 표본 sessionId={} "
                                        + "표본 roomId={} cause={}", failures, ls.sessionId(),
                                ls.session().roomId(), e.getClass().getSimpleName());
                    }
                    return Mono.empty();
                });
    }

    /**
     * 재등록 실패 누적.
     *
     * <p>{@code public} 인 이유는 {@code ChatSessionGaugeConfig}(다른 패키지)가
     * {@code chat.sessions.heartbeat_failures} 를 이 접근자에 배선하기 때문이다.
     *
     * <p>⚠️ <b>0 이 "건강하다" 를 뜻하지 않는다.</b> 회차가 <b>실패</b>하면 늘지만 아예
     * <b>안 끝나면</b> 늘지 않는다 — 위 주기 javadoc 이 그 갈래를 적는다. 회차 길이를 재는 신호는
     * 아직 없으므로, 이 수로 "재등록이 돌고 있다" 를 결론 내리면 침묵이 안전으로 읽힌다.
     */
    public long heartbeatFailures() {
        return heartbeatFailures.get();
    }

    /**
     * 입장 ROOM_INFO 를 보낸 <b>뒤</b> 부른다 — 그 방의 마지막으로 보낸 수를 지워 다음 회차가 수가 같아도
     * 한 번 보내게 한다. 입장 때 받은 수는 캐시를 거친 값이라 지금 방의 수와 다를 수 있다.
     *
     * <p>등록 안에서 지우면 안 되는 이유: 등록과 입장 ROOM_INFO 사이에 회차가 끼면 그 회차가 맞는 수를
     * 보내고 기록한 뒤 캐시의 수가 <b>나중에</b> 도착한다. 다음 회차는 기록과 같은 수라 건너뛰고, 틀린 수가
     * 인원이 다시 변할 때까지 남는다. 보낸 뒤에 지우면 그 사이에 낀 회차의 기록도 함께 지워진다.
     *
     * <p>순서를 닫는 것은 둘이다. 회차가 기록({@code lastPushedCount.put})을 보내기 <b>전에</b> 하므로, 낀 회차가
     * 있었다면 이 지우기 전에 이미 기록이 있다. 그리고 같은 세션으로의 전송은 {@code emitSerially} 로 직렬이라
     * 입장 프레임 뒤에 쌓인 회차 프레임이 앞지르지 못한다. 입장 프레임이 경합으로 버려져도 지우기는 돌아
     * 다음 회차가 보낸다 — 실패가 안전한 쪽으로 난다.
     */
    void forgetLastPushedCount(UUID roomId) {
        lastPushedCount.remove(roomId);
    }

    /**
     * 로컬 세션이 있는 방마다 시청자 수를 다시 읽고, <b>변했으면</b> {@code ROOM_INFO} 를 다시 보낸다.
     *
     * <p>지금까지 그 수는 입장 시 1회뿐이라 <b>고정 스냅샷</b>이었다 — 앞 작업이 좀비 항목을 걷어내
     * 수를 정확하게 만들었는데, 그 정확한 수를 이미 접속한 세션에 전달할 수단이 없었다.
     *
     * <p><b>프레임을 새로 만들지 않는다.</b> 프론트 가이드의 클라 핸들러가 순수 상태 setter
     * ({@code setActiveCount} + {@code setOwnerMode})라 {@code ROOM_INFO} 반복 수신이 멱등이고,
     * 반대로 모르는 {@code type} 을 어떻게 다루는지는 계약에 적혀 있지 않아 새 타입이 더 위험하다.
     * ⚠️ 그래도 <b>프론트 계약 추가</b>다 — "입장 시 1회만" 이 그 문서에 명시돼 있으니 함께 고쳐야 한다.
     *
     * <p><b>변했을 때만 보낸다.</b> 매 회차 뿌리면 조용한 방도 세션 수만큼의 프레임을 계속 쓴다 —
     * 큰 방에서는 그게 이 기능의 비용 전부가 된다. 예외는 마지막으로 보낸 수가 없을 때(이 Pod 의 첫
     * 회차, 또는 그 뒤 누가 들어와 {@code register} 가 지운 뒤)이고 그때는 수가 같아도 한 번 보낸다.
     * 입장이 잦은 방은 대개 인원도 같이 변해 어차피 보내므로, 이 예외가 더하는 비용은 작다.
     *
     * <p><b>수를 모를 때는 보내지 않는다.</b> {@code activeCount} 는 null 로 "모른다" 를 뜻할 수 있는데
     * (조회 실패), 그걸 갱신으로 보내면 <b>보이던 숫자가 사라진다</b>. 입장 시의 "모름" 과 다른 상황이다.
     *
     * <p>세션마다 {@code resolver} 로 자기 {@code isRoomOwner} 를 받는다 — 한 프레임을 공유하면 방 주인
     * UI 가 전원에게 켜지거나 방 주인에게서 꺼진다.
     *
     * <p>Pod 마다 독립적으로 읽으므로 같은 방의 시청자가 한순간 서로 다른 숫자를 볼 수 있다
     * (조회 캐시만큼). 부드러운 수치라 받아들인다 — 맞추려면 Pod 간 합의가 필요하고 그건 이 값이
     * 가질 가치가 없다.
     */
    Mono<Void> pushViewerCounts() {
        return Flux.fromIterable(List.copyOf(roomSessions.keySet()))
                .concatMap(this::pushViewerCount)
                .then();
    }

    private Mono<Void> pushViewerCount(UUID roomId) {
        // 캐시(getActiveCount)를 거치지 않는다. 그 캐시는 <b>입장 폭주</b>를 막으려고 조회를 억제하는
        // 장치인데, 이 타이머가 하려는 일이 정확히 "변화를 관측" 이라서 억제를 거치면 캐시 수명 안의
        // 변화를 못 본다. 그리고 억제할 이유도 없다 — 이쪽은 방당 회차당 한 번으로 이미 유계다.
        return sessionRepository.count(roomId)
                .flatMap(count -> {
                    Long previous = lastPushedCount.put(roomId, count);
                    if (!roomSessions.containsKey(roomId)) {
                        // 이 회차의 조회가 도는 사이에 방의 마지막 세션이 빠졌다. unregister 가 이미
                        // 항목을 회수했는데 위 put 이 그걸 되살린 것이고, 아무도 재입장하지 않으면
                        // 영구히 남는다 — 방송마다 하나씩 쌓이는 누수다. 자기 쓰기를 되돌린다.
                        lastPushedCount.remove(roomId);
                        return Mono.<Void>empty();
                    }
                    if (count.equals(previous)) {
                        // previous == null 은 보낸다 — 이 Pod 가 그 방을 처음 보는 회차이거나, 그 뒤에 누가
                        // 들어와 입장 ROOM_INFO 를 받은 뒤 값이 지워진 회차다(forgetLastPushedCount). 입장 ROOM_INFO 는 기준선이 못 된다: 캐시를
                        // 거친 값이라 이 회차의 수와 다를 수 있고, 세션마다 들어온 시각이 달라 방 하나에
                        // 기준선이 하나로 정해지지도 않는다. 한때 null 을 건너뛰었는데, 그러면 입장 때 받은
                        // 수가 틀렸을 때 인원이 다시 변할 때까지 그 숫자가 화면에 남았다.
                        return Mono.empty();
                    }
                    // 뷰 둘을 미리 만들어 세션마다 고르기만 한다. 세션마다 새로 만들면 같은 내용인
                    // 객체가 세션 수만큼 생기고, 그러면 팬아웃의 직렬화 메모가 한 건도 안 걸려
                    // 직렬화가 다시 세션 수에 비례한다(OutboundFrame 참조).
                    OutboundMessage ownerInfo = OutboundMessage.roomInfo(count, true);
                    OutboundMessage viewerInfo = OutboundMessage.roomInfo(count, false);
                    return sendToRoomGated(roomId,
                            session -> session.isRoomOwner() ? ownerInfo : viewerInfo);
                })
                .onErrorResume(e -> {
                    // 수를 모르면 보내지 않는다 — 다음 회차가 받는다. 여기서 터뜨리면 그 뒤로 이 Pod 의
                    // 모든 방 갱신이 멈춘다(concatMap 상류가 끝난다).
                    if (shouldLogViewerPushFail()) {
                        log.warn("시청자 수 갱신 실패 — 이번 회차 skip(다음 회차가 받는다) roomId={} cause={}",
                                roomId, e.getClass().getSimpleName());
                    }
                    return Mono.empty();
                });
    }

    /** 마지막으로 보낸 수를 들고 있는 방의 개수 — 누수 확인용(테스트 진입점). */
    int trackedViewerCounts() {
        return lastPushedCount.size();
    }

    /**
     * roomId → 그 방의 sessionId 집합. {@link #local}의 보조 인덱스다.
     *
     * <p>방 fan-out이 Pod 전체 세션을 훑지 않게 한다 — 메시지 1건마다 전체 스캔이면 다른 방 세션 수에
     * 비례해 비용이 붙고, 그걸 이벤트루프에서 한다. 갱신은 {@code compute} 계열로만 해서 등록/해제가
     * 서로 끼어들지 않게 하고, 방이 비면 항목 자체를 지운다(죽은 방 누적 방지).
     */
    private final Map<UUID, Set<String>> roomSessions = new ConcurrentHashMap<>();

    /**
     * 같은 사람이 같은 방에 동시에 열 수 있는 세션 수의 상한.
     *
     * <p><b>왜 (유저, 방) 단위인가.</b> 막으려는 것은 "유효한 룸 토큰 한 장으로 세션을 계속 여는 것"
     * 이고, 룸 토큰은 한 방에 묶여 있다({@code role}·{@code owner}·{@code roomId} 가 클레임이다).
     * 토큰이 단건용이 아니라 TTL(약 90초) 안에서 재사용 가능하므로({@code modules/live/AGENTS.md} —
     * "Not single-use — replayable within the TTL") 그 창 안에 같은 토큰으로 몇 번이고 핸드셰이크할 수
     * 있다. 그 창을 닫는 가장 좁은 단위가 (유저, 방)이다. 유저 단위로 넓히면 방을 여러 개 보는
     * 관리자를 막고, 방 단위로 넓히면 인기 방의 정상 시청자를 막는다.
     *
     * <p><b>왜 5 인가.</b> 정상 사용자가 같은 방에 동시에 여는 수에서 나온 값이다 — 휴대폰 + 데스크톱
     * + 새로고침 경합(앞 세션이 아직 안 걷혔다)까지 세도 3 을 넘지 않는다. <b>한때 그 셋만 셌다</b>:
     * 소켓이 조용히 끊긴 자리가 생존 확인의 첫 점검(기한 + 간격, 기본 약 2분)까지 남는다는 것을
     * 세지 않아, 모바일 핸드오버로 조용히 다섯 번 끊긴 사람이 거부되는 경로가 열려 있었다. 그 창은
     * 값을 키워서가 아니라 {@link #reapUnresponsiveIfFull} 로 닫았다 — 값을 키우면 남용 한도가
     * 같이 커지는데 그 창은 몇 개가 필요한지 정해지지 않는다. 용량에서 나온 값이
     * <b>아니다</b>: 앱 쪽 세션당 2,349 bytes 라 5 든 50 든 메모리는 구분되지 않는다
     * ({@code ChatSessionGaugeConfig} 에 실측이 있다). 그래서 이 값은 부하 테스트를 기다릴 이유가
     * 없었고, 한때 용량 상한과 한 덩이로 묶여 미뤄져 있었다.
     *
     * <p>⚠️ <b>게스트에는 걸리지 않는다.</b> 비로그인 입장은 <b>입장마다 새 임의 userId</b>를 받으므로
     * ({@code EnterLiveService} — "매 입장마다 새 임의 userId를 부여하고 role=GUEST") 게스트 연결은
     * 전부 다른 키가 되어 이 상한에 닿지 않는다. 즉 이 상한이 닫는 것은 <b>회원 토큰 재사용</b>
     * 한 갈래이고, 인증 없이 {@code enter} 를 반복해 토큰을 받아 가는 갈래는 그대로 열려 있다.
     * 그쪽은 발급 측(live)의 레이트리밋 문제라 이 자리에서 닫을 수 없다 — "연결 수 상한이 생겼다" 로
     * 읽히면 안 되는 이유다.
     *
     * <p>⚠️ <b>파드 로컬이다 — 전역 상한이 아니다.</b> {@link #userRoomSessions} 는 이 싱글톤의
     * 인스턴스 필드라 실효 상한은 <b>상한 × 레플리카 수</b>다. 공격자가 LB 라우팅을 통제할 필요도 없다 —
     * 핸드셰이크가 흩어지면 각 파드의 {@code compute} 가 서로 다른 맵을 봐서 전부 통과한다. 이 파일은
     * 다중 파드를 설계 전제로 못 박고 있고(아래 계정 팬아웃 비용이 "파드 6개" 기준이다), 크로스 파드
     * 데이터도 이미 있다({@code ChatSessionRepository.add} 가 Redis HASH 에 userId 를 적는다).
     * 즉 파드 로컬은 <b>제약이 아니라 선택</b>이다 — 전역으로 올리면 입장마다 Redis 왕복이 하나 붙고,
     * 남용 한도를 상한에서 상한×N 으로 두는 대가가 그 왕복보다 싸다고 봤다. 선택이므로 적어 둔다.
     *
     * <p>⚠️ <b>이 상한이 "피해자 입장 차단" 이라는 능력을 새로 만들었다.</b> 상한 키가 토큰에서만
     * 오고({@code RoomTokenVerifier} 의 {@code claims.getSubject()} 하나), 토큰이 TTL 안에서 재사용
     * 가능하고, 세션이 토큰보다 훨씬 오래 살고(기본 수명 60분, 세션 중 재검증 없음 — {@code verify}
     * 호출부는 핸드셰이크 하나다), 상한이 <b>새 연결을 거부</b>하므로 — 90초짜리 자격증명으로 자리를
     * 채워 두면 정당한 소유자가 그 파드에서 최대 수명만큼 입장 거부된다. 그 토큰 재사용은
     * {@code RoomTokenVerifier} 가 <b>이미 알고 받아들인</b> 것인데, 그 수용은 "결과가 엿듣기로
     * 한정된다" 는 전제에서 나왔고 이 상한이 그 전제를 바꿨다.
     *
     * <p>그래서 <b>가장 오래된 세션을 밀어내지 않는다.</b> 한때 이 비교가 능력의 <b>종류</b>만
     * 봤는데, 리뷰가 두 칸을 더 적게 했다 — 그 둘이 거부 쪽에 불리하다:
     * <pre>
     *                  거부(현재)                        evict-oldest
     * 공격자 능력      그 (유저, 방) 입장을 결정론적 차단   피해자의 살아 있는 세션 1개를 끊음
     * 지속             51~60분 (세션 수명, 지터 −15%)      ≤90초 (재점유마다 유효 토큰이 필요)
     * 피해자 수단      없음* — 자리가 빌 때까지 전부 1008   항상 — 재접속하면 최신 자리를 되찾음
     * </pre>
     * (*"없음" 은 <b>공격자가 상한 × 레플리카를 완주한 뒤</b>의 값이다 — 이 상한이 파드 로컬이라
     * 완주 전에는 피해자의 재접속도 흩어져 남은 파드에 붙는다. 핸드셰이크에 레이트리밋이 없어 토큰
     * TTL 안에 완주가 가능하므로 표에는 완주 후 값을 적는다. LB 어피니티가 그걸 막는지는 <b>이
     * 저장소에서 확인할 수 없다</b> — 매니페스트가 여기 없다. 표의 "≤90초" 는 <b>불변식이 아니라
     * 배포 설정값</b>이다 — 룸 토큰 만료가 설정이고 그 쪽
     * 검증은 양수까지만 본다. 설정을 길게 잡으면 그 칸이 같이 늘어난다.)
     * 비대칭의 원인은 <b>토큰 TTL 이 evict 쪽만 묶는다</b>는 것이다 — 거부의 피해는 이미 성립한
     * 세션이 나르므로 토큰이 죽은 뒤에도 수명만큼 간다.
     *
     * <p>그래도 거부를 택한 이유는 <b>하나</b>다 — 능력의 종류가 다르다. 지금은 "못 들어온다" 이고
     * 그쪽은 "쫓겨난다" 이며, 후자는 방송을 보는 중에 끊기는 것이다. 위 표와 이 한 줄이 결정의
     * 전부이고, 표의 두 칸이 거부 쪽에 불리하다는 것도 그대로 둔다.
     *
     * <p>⚠️ 한때 근거를 셋으로 적었는데 <b>둘이 틀렸다.</b> (2) 로 "evict 는 살아 있는 소켓의
     * flush 를 접속 경로에 올린다" 고 적었는데, {@code terminate} 는 <b>소켓 I/O 를 하지 않는다</b> —
     * closeStatus CAS · sink {@code tryEmitComplete} · 제어 채널 {@code tryEmitValue} 뿐이고 실제
     * {@code session.close()} 는 피해자 자신의 체인에서 다른 스레드가 쓴다. 배수도 반대다: 빼낸
     * reap 은 최대 5건 × 50ms 였고 evict 는 1건 × 50ms 다. (3) 으로 "정상 사용자가 걸리는 경로는
     * evict 없이도 닫힌다" 고 적었는데, {@link #reapUnresponsiveIfFull} 이 그 창을 닫지 못한다는
     * 사실(그 javadoc 참고)이 그 근거를 없앴다. 재지 않고 지어낸 논증이라 지운다 — 남는 하나로도
     * 결정은 서지만, 셋이라고 적어 둔 동안 그 결정이 실제보다 단단해 보였다.
     *
     * <p>근본 수정은 live 가 룸 토큰에 {@code jti} 를 넣어 단건용으로 만드는
     * 것이고 그건 이 저장소 밖이다 — 완화는 토큰이 쿼리가 아니라 헤더로 다니는 것과 차단이 파드
     * 범위라는 것, 둘뿐이다.
     *
     * <p><b>이 검사는 입장 게이트 뒤에 있다</b> — 거부되는 연결도 게이트의 Redis 왕복(종료·강퇴 마커)을
     * 이미 쓴 상태다. 게이트 앞으로 옮기면 그 왕복을 아낄 수 있고 userId 는 토큰 검증(로컬)만으로
     * 나오므로 기술적으로 가능하지만, <b>옮기지 마라</b>: 검사와 등재가 쪼개지면 동시 접속 여럿이
     * 같은 수를 읽고 다 통과한다(순수 분리 되돌림 실측: 1라운드 20스레드에서 탐지 42%, 서명
     * {@code was: 6} — 한때 여기 "20개가 전부 들어왔다" 로 적었는데 그 수는 창을 {@code sleep} 으로
     * 넓힌 변형의 것이고, {@code SessionAdmissionCapTest} 가 그 숫자를 철회했다). 아끼는 것은 왕복 셋이고
     * 잃는 것은 상한 자체다.
     */
    public static final int DEFAULT_MAX_SESSIONS_PER_USER_PER_ROOM = 5;

    /** 이 파드가 실제로 집행하는 값. 상수가 아니라 필드인 이유는 운영이 바꿀 수 있어야 해서다. */
    private final int maxSessionsPerUserPerRoom;

    /** 상한을 세는 키. (유저, 방) 쌍이다 — 상한의 단위가 그것이라는 사실을 타입으로 남긴다. */
    private record UserInRoom(UUID userId, UUID roomId) {
    }

    /**
     * (유저, 방) → 그 조합의 sessionId 집합. {@link #roomSessions} 와 같은 종류의 보조 인덱스다.
     *
     * <p>세지 않고 {@link #local} 을 훑으면 접속 1건이 Pod 전체 세션 수에 비례한다 — 재접속 폭풍이
     * 정확히 그 둘(접속 수 × 세션 수)이 동시에 큰 구간이라, 상한을 넣으려고 만든 코드가 그 구간의
     * 비용이 된다. 방이 비면 항목을 걷어내는 것도 같은 이유다(죽은 키 누적 방지).
     */
    private final Map<UserInRoom, Set<String>> userRoomSessions = new ConcurrentHashMap<>();

    /** 상한으로 거부한 누적 수. 로그를 솎아도 규모는 남긴다 — 정상 시청자를 막고 있는지는 이 수로 본다. */
    private final AtomicLong admissionCapped = new AtomicLong();

    /** 죽은 소켓이 들고 있던 자리를 거둔 누적 수. 거부와 짝으로 봐야 공격과 핸드오버가 갈린다. */
    private final AtomicLong slotsReaped = new AtomicLong();

    /** 상한 거부 로그를 마지막으로 남긴 시각. 첫 발생이 반드시 남도록 간격만큼 과거로 초기화한다. */
    private final AtomicLong lastAdmissionCapLogNanos =
            new AtomicLong(System.nanoTime() - DROP_LOG_INTERVAL_NANOS);

    /**
     * 방별 시청자 수 캐시. 입장할 때마다 Redis HASH를 통째로 읽어오는 비용을 끊는다.
     *
     * <p>없으면 입장 하나가 그 방의 세션 수만큼 값을 전송받는다 — 방이 커질수록 입장이 비싸지고,
     * 그 비싸진 입장이 다시 방을 키우므로 총량이 세션 수의 제곱으로 간다. 인기 방에서 동시에 몰릴 때
     * 그 부담이 그대로 Redis 단일 스레드에 실린다.
     *
     * <p>잠깐 낡아도 되는 값이라 캐시가 성립한다 — 이 수치는 입장 시 한 번만 내려가고 이후 갱신 push가
     * 없어서, 클라가 보는 값은 어차피 그 시점의 스냅샷이다. 방이 비면 {@code unregister}가 같이 걷어낸다.
     */
    private final Map<UUID, CachedCount> activeCountCache = new ConcurrentHashMap<>();

    private record CachedCount(long value, long expiresAtNanos) {
    }

    /** 경합 재시도 소진으로 버린 메시지 수. 유실을 조용히 넘기지 않기 위한 카운터. */
    private final AtomicLong droppedOnContention = new AtomicLong();

    /** 수명 상한으로 끊은 누적 수 — 로그를 솎아도 규모는 남긴다. */
    private final AtomicLong lifetimeExpiredCount = new AtomicLong();

    /** 수명 상한 로그를 마지막으로 남긴 시각. 첫 발생이 반드시 남도록 간격만큼 과거로 초기화한다. */
    private final AtomicLong lastLifetimeExpiryLogNanos =
            new AtomicLong(System.nanoTime() - DROP_LOG_INTERVAL_NANOS);

    /** 만든 fan-out 공유 예산 수 — fan-out당 하나라는 불변식을 테스트가 고정한다({@link #budgetsCreated}). */
    private final LongAdder budgetsCreated = new LongAdder();

    /**
     * 방 종료를 다시 확인하는 간격. 종료 신호를 놓친 Pod가 끝난 방에 글을 받아주는 구간의 상한이 된다.
     * 짧게 잡을수록 그 구간은 줄지만 정상 전송에 붙는 Redis 왕복 빈도가 오른다.
     */
    static final long ROOM_ALIVE_RECHECK_INTERVAL_NANOS = Duration.ofSeconds(30).toNanos();

    /** 시청자 수 캐시 수명. 짧게 잡아도 몰리는 순간의 중복 조회는 대부분 걷힌다. */
    private static final long ACTIVE_COUNT_CACHE_NANOS = Duration.ofSeconds(3).toNanos();

    /**
     * emit 경합 드롭 로그를 마지막으로 남긴 시각. 첫 발생이 반드시 남도록 간격만큼 과거로 초기화한다.
     *
     * <p>⚠️ <b>사유마다 따로다.</b> 한때 이 래치 하나를 세 실패 모드가 공유했고(경합 드롭 · 재등록
     * 실패 · 시청자 수 갱신 실패), 그러면 먼저 찍은 쪽이 다른 사유의 <b>첫 발생</b>을 통째로 덮는다.
     * 재등록 실패와 시청자 수 갱신 실패는 같은 타이머 회차에서 연달아 나므로 10초 창이 아니라
     * <b>한 회차</b> 안에서 서로를 가렸다. 이 저장소가 같은 결함을 이미 두 번 이름 붙여 고쳤다
     * ({@code EntryGate} · {@code SendChatService}) — 형제 둘({@link #lastAdmissionCapLogNanos} ·
     * {@link #lastLifetimeExpiryLogNanos})이 그 결론이고 이 셋이 마지막으로 남은 공유였다.
     */
    private final AtomicLong lastContentionDropLogNanos =
            new AtomicLong(System.nanoTime() - DROP_LOG_INTERVAL_NANOS);

    /** 재등록 실패 로그를 마지막으로 남긴 시각. {@link #lastContentionDropLogNanos} 의 사유별 짝이다. */
    private final AtomicLong lastReregisterFailLogNanos =
            new AtomicLong(System.nanoTime() - DROP_LOG_INTERVAL_NANOS);

    /** 시청자 수 갱신 실패 로그를 마지막으로 남긴 시각. {@link #lastContentionDropLogNanos} 의 사유별 짝이다. */
    private final AtomicLong lastViewerPushFailLogNanos =
            new AtomicLong(System.nanoTime() - DROP_LOG_INTERVAL_NANOS);

    /**
     * 한 커넥션의 채널 묶음.
     *
     * <p><b>{@code sink}(데이터) 와 {@code terminate}(제어)를 나눈 이유</b>: 종료를 sink complete로만 알리면
     * 그 신호가 버퍼에 쌓인 메시지 <i>뒤에</i> 줄을 선다. 소켓을 읽지 않는 클라에겐 영영 닿지 않아 연결·세션
     * 항목·방 구독이 회수되지 않는다. 제어 신호는 데이터 큐를 타지 않아야 한다.
     *
     * <p>{@code closeStatus}는 종료 사유 — complete로 곱게 닫히든 제어 채널로 끊기든 같은 코드로 닫기 위해
     * 따로 보관한다(complete 신호엔 코드를 실을 수 없다).
     */
    private record LocalSession(String sessionId, ChatSession session, Sinks.Many<OutboundFrame> sink,
            Sinks.One<CloseStatus> terminate, AtomicReference<CloseStatus> closeStatus,
            AtomicLong lastRejectionReplyNanos, AtomicLong rateLimitedUntilNanos,
            AtomicLong lastRoomAliveCheckNanos, AtomicBoolean roomEnded,
            AtomicLong lastInboundNanos, AtomicLong inboundAllowedAtNanos) {
    }

    /**
     * transport 전용: 레이트리밋에 걸렸다는 사실과 해제 시각을 이 세션에 적어둔다.
     *
     * <p>거부 자체는 레이트리밋이 하지만, <b>거부하는 비용</b>은 제한하지 못한다 — 거부 한 번에 Redis 왕복이
     * 세 번(강퇴·밴 조회·레이트리밋 스크립트) 든다. 회선 속도로 밀면 그 비용이 그대로 반복된다.
     * 해제 시각을 알고 있는 동안은 같은 답을 다시 물을 이유가 없으므로 여기에 적어두고 로컬에서 끊는다.
     */
    public void recordRateLimited(String sessionId, long retryAfterSeconds) {
        LocalSession ls = local.get(sessionId);
        if (ls == null) {
            return;
        }
        // 상한을 둔다 — 값 출처가 바뀌어 큰 수가 들어오면 Duration 환산이 ArithmeticException 을
        // 던지고, 이 메서드는 onInbound 의 onErrorResume 람다에서 불리므로 그 던짐이 인바운드
        // Flux 의 onError 가 되어 연결을 끊는다. 실측: ofSeconds(Long.MAX_VALUE).toNanos() 가
        // 던진다(경계는 9_223_372_036 초 — 그 값은 안 던진다). 레이트리밋 창은 분 단위를 넘길
        // 이유가 없다.
        //
        // 음수는 여기서 접지 않는다. 한때 Math.max(0, ..) 가 있었고 그 자리 주석이 그것도 예외를
        // 막는 것처럼 적었는데 거짓이었다 — 음수는 던지지 않는다(실측:
        // ofSeconds(-5).toNanos() = -5_000_000_000). 그리고 이 필드의 독자는
        // rateLimitRetryAfterSeconds 하나뿐이고(전수: 쓰는 곳 1 · 읽는 곳 1) 그쪽이 이미
        // remaining <= 0 을 0 으로 접는다. 즉 그 가드는 도달 불가였고, 두 겹이 남아 있는 동안은
        // 어느 쪽을 지워도 테스트가 초록이라 둘 다 묶이지 않았다. 지우니 남은 한 겹이 묶인다.
        long clamped = Math.min(retryAfterSeconds, MAX_RATE_LIMIT_WINDOW_SECONDS);
        ls.rateLimitedUntilNanos().set(System.nanoTime() + Duration.ofSeconds(clamped).toNanos());
    }

    /** 로컬 레이트리밋 창 상한 — 어댑터가 주는 값이 커져도 여기서 잘린다. */
    private static final long MAX_RATE_LIMIT_WINDOW_SECONDS = 60;

    /**
     * transport 전용: 아직 레이트리밋 창 안이면 남은 초, 아니면 0.
     *
     * <p><b>거부에만 쓴다.</b> 0이 나와도 통과시키지 않고 평소대로 레이트리밋을 묻는다 — 이 값은 세션 로컬이라
     * 같은 유저의 다른 탭이나 다른 Pod가 소모한 몫을 모른다. "확실히 막혀 있다"만 여기서 답할 수 있다.
     */
    public long rateLimitRetryAfterSeconds(String sessionId) {
        LocalSession ls = local.get(sessionId);
        if (ls == null) {
            return 0;
        }
        long remaining = ls.rateLimitedUntilNanos().get() - System.nanoTime();
        if (remaining <= 0) {
            return 0;
        }
        // 올림한다 — 절삭하면 1초 미만이 0이 되어 "제한 없음"과 구분되지 않고, 클라 카운트다운도 짧아진다.
        return (remaining + 999_999_999L) / 1_000_000_000L;
    }

    @Override
    public Mono<Void> register(String sessionId, ChatSession session) {
        // 상한 검사와 등재를 <b>한 원자 구간</b>에 묶는다. 세어 보고 나중에 넣으면 동시 접속 둘이
        // 같은 수를 읽고 둘 다 통과해 상한을 넘긴다 — compute 의 재매핑 함수는 그 키에 대해 직렬화되므로
        // "셋을 보고 넣는다" 가 쪼개지지 않는다. 판정을 배열로 받는 것은 그 안에서만 쓰는 값이라서다
        // (같은 키에 동시 실행이 없으므로 가시성 문제도 없다).
        //
        // ⚠️ 이 메서드는 {@code Mono.defer} 로 감싸여 있지 않다 — 자리 증가는 <b>조립 시점에 즉시</b>
        // 일어난다. 한때 여기 "그 구간은 Mono 조립뿐이라 도달 불가" 라고 적었는데 <b>두 문장이 다
        // 틀렸다</b>: 그 구간에는 local.put 과 주입 협력자 호출(특권 뷰 감사)과 roomSessions.compute
        // 가 있고, 이 파일의 테스트가 그 협력자를 던지게 해 실제로 그 구간을 밟는다. 그리고 "항목이
        // 영구히 남는다" 도 이제 아래 try/finally 가 되돌린다 — 불변식이 "도달 불가" 에서 "집행" 으로
        // 바뀐 것이 이 설계의 내용이다. 다음 사람이 "도달 불가" 를 읽고 그 가드를 걷으면 안 된다.
        //
        // local.put 보다 <b>앞</b>이어야 한다. 뒤에 두면 거부된 연결이 이미 local 에 들어가 있어
        // 게이지·팬아웃이 그 세션을 한 번 보고, cleanup 이 지우기 전까지 시청자 수가 틀린다.
        // ⚠️ <b>세션 레코드를 자리 확보 앞에서 만든다.</b> 한때 자리를 넣은 뒤 이 자리에서 만들었고,
        // 그러면 자리 추가와 {@code local.put} 사이에 <b>할당</b>이 들어와 OutOfMemoryError 로 던질 수
        // 있는 창이 생긴다. 그 창은 테스트로 밟을 수단이 없어(남는 것이 Error 뿐이다) 그 구간의
        // 보상은 CI 가 영원히 확인해 주지 않는다 — 실제로 그 보상이 세 라운드에 걸쳐 두 번
        // 좁아졌고 두 번 다 스위트가 초록이었다.
        //
        // ⚠️ 대가로 <b>불변식이 바뀌었다.</b> 아래 레코드는 nanoTime 셋을 들고 있고 그 원점이 이제
        // 자리 확보·거부 판정보다 앞선다. 포함관계는 보존된다 — 생존 확인의 두 술어가 같은 필드를
        // 읽으므로 원점이 δ 앞당겨지면 둘이 같이 δ 앞당겨진다. 실제로 앞당겨지는 것은 30s·90s 창
        // 둘이고 그 사이 구간은 맵 compute 하나와 분기(마이크로초)다. 나머지 둘(거부 응답 간격·
        // 레이트리밋)은 원점이 앞설수록 더 안전한 방향이다.
        //
        // 그래서 금지가 "던질 수 있는 코드" 에서 <b>"느린 코드"</b> 로 바뀌었다 — 이 생성과
        // local.put 사이에 I/O 나 락을 넣으면 그 창 둘이 그만큼 짧아진다.
        //
        // 그래서 테스트를 만드는 대신 <b>창을 없앴다</b>. 이 아래로는 자리 추가 ~ local.put 사이에
        // 할당이 없다. 대가는 거부 1건마다 버려지는 아웃바운드 큐 하나(256슬롯, 약 2.3KB)이고,
        // 거부 경로가 남용 경로라 공짜가 아니다 — 다만 그 1건은 이미 WS 핸드셰이크와 토큰 검증과
        // 입장 게이트의 Redis 왕복 넷을 쓴 뒤다.
        LocalSession pending = new LocalSession(sessionId, session,
                Sinks.many().unicast().onBackpressureBuffer(
                        Queues.<OutboundFrame>get(OUTBOUND_BUFFER_SIZE).get()),
                Sinks.one(), new AtomicReference<>(),    // closeStatus는 종료가 정해질 때 채워진다(null=미정)
                // 둘 다 nanoTime 기준이라 0을 센티널로 쓰면 안 된다 — nanoTime 원점은 임의고 음수일 수 있어,
                // 0과 비교하면 등록 직후부터 "창 안"으로 오판해 전송이 통째로 막힌다.
                new AtomicLong(System.nanoTime() - REJECTION_REPLY_INTERVAL_NANOS),
                new AtomicLong(System.nanoTime()),
                // 입장 게이트가 방금 확인했으므로 창을 지금부터 연다 — 첫 프레임에서 또 묻지 않는다.
                new AtomicLong(System.nanoTime()),
                new AtomicBoolean(),
                // 생존 시계도 지금부터 — 방금 핸드셰이크를 마쳤으므로 응답이 없는 상태가 아니다.
                new AtomicLong(System.nanoTime()),
                // 인바운드 상한도 지금부터 — 버스트 전부를 쥐고 시작한다.
                new AtomicLong(System.nanoTime()));

        boolean[] admitted = {false};
        userRoomSessions.compute(new UserInRoom(session.userId(), session.roomId()), (key, sessionIds) -> {
            Set<String> ids = sessionIds == null ? ConcurrentHashMap.newKeySet() : sessionIds;
            if (ids.size() < maxSessionsPerUserPerRoom) {
                ids.add(sessionId);
                admitted[0] = true;
            }
            return ids;
        });
        // ⚠️ <b>자리를 넣은 다음부터 반환까지 전부 이 try 안이다.</b> 그 구간에서 던지면 회수 경로
        // 둘이 다 못 걷기 때문이다 — unregister 는 local.get 이 null 이라 블록을 건너뛰고,
        // reapUnresponsiveIfFull 은 isUnresponsive 가 local 에 없는 sessionId 에 "살아 있음" 을
        // 준다. 그러면 그 유저는 프로세스 재시작 전까지 그 방에 못 들어온다 — 이 파일이 스스로
        // "상한 코드가 만들 수 있는 최악의 결함" 이라 부른 그것이다.
        //
        // ⚠️ "실패하면 되돌린다" 의 대상은 <b>넷</b>이다 — 자리·local·방 항목·캐시 둘.
        // 한때 이 try 가 <b>local.put 하나만</b> 감쌌다. 그러면 집행 대상이 사실상 OOM 뿐이고
        // (put 과 생성자들은 Error 외에 던질 경로가 없다) 정작 던질 수 있는 둘 — 주입 협력자를
        // 부르는 감사 블록과 roomSessions.compute — 이 가드 <b>밖</b>에 남았다. 그 상태가 더
        // 나빴다: 거기서 던지면 자리뿐 아니라 local 항목까지 남는다.
        //
        // ⚠️ 근거가 한 번 낡았다. 한때 "호출부가 register(...).then(...).doFinally(cleanup) 이라
        // doFinally 가 애초에 조립되지 않는다" 고 적었는데, 호출부가 Mono.defer{...}.doFinally(...)
        // 로 바뀌어 <b>동기 예외에서도 doFinally 는 돈다</b>(실측). 가드가 여전히 필요한 이유는
        // 다른 것이다 — 그 cleanup 이 부르는 unregister 가 local.get 으로 키를 만들고, 이 구간에서
        // 던지면 local 에 항목이 없어 자리를 못 걷는다. 결론은 같고 근거만 교체됐다.
        //
        // 거부 분기도 이 안에 둔다 — 자리 추가와 가드 사이에 <b>한 줄도 들어가지 않게</b> 하려는
        // 것이고, 인접성에 기대지 않는다는 것이 이 배치의 값이다.
        //
        // 그 경로의 finally 가 무해한 근거는 "remove 가 무연산" 한 단계가 아니다 — finally 는 자리와
        // local 과 방 항목과 캐시 둘을 되돌리고, 그중 <b>방 항목 삭제와 캐시 둘은 remove 의 무연산
        // 여부와 무관하게 {@code sessionIds.isEmpty()} 하나로 갈린다.</b> 성립하는 근거는 더 센
        // 불변식이다: <b>{@code roomSessions} 에 항목이 있으면 그 집합은 비어 있지 않다</b>(값 집합을
        // 바꾸는 지점이 등록과 되돌리기 둘뿐이고 둘 다 같은 키의 compute 안이며, 비는 즉시 매핑을
        // 걷는다). 그래서 "키는 있는데 집합이 빈" 상태가 구성 자체가 되지 않는다.
        boolean registered = false;
        try {
            if (!admitted[0]) {
                long capped = admissionCapped.incrementAndGet();
                if (shouldLogAdmissionCap()) {
                    // 표본 하나와 Pod 누적을 함께 적는다 — 드롭 로그와 같은 표기다. 이 줄이 늘어나면
                    // 공격이 아니라 상한이 낮은 것일 수 있으니, 사람이 그 둘을 가를 수 있게 둘 다 남긴다.
                    log.warn("동시 세션 상한 초과로 입장 거부 — 표본 userId={} 표본 roomId={} 상한={} 누적거부={}",
                            session.userId(), session.roomId(), maxSessionsPerUserPerRoom, capped);
                }
                // 던지지 않고 Mono.error 로 돌려준다 — 핸들러의 onErrorResume(EntryDeniedException) 이
                // SYSTEM code 를 실어 1008 로 닫는 경로를 이미 갖고 있고, 거부는 그 모양이 맞다.
                return Mono.error(new EntryDeniedException(EntryDeniedException.Reason.TOO_MANY_SESSIONS));
            }

            // ⚠️ <b>자리 증가와 local.put 사이에서 던지면 자리가 영구히 남는다.</b> 회수 경로 둘이
            // 다 못 걷기 때문이다 — unregister 는 local.get 이 null 이라 블록을 건너뛰고,
            // reapUnresponsiveIfFull 은 isUnresponsive 가 local 에 없는 sessionId 에 "살아 있음" 을
            // 준다. 그러면 그 유저는 프로세스 재시작 전까지 그 방에 못 들어온다 — 이 파일이 스스로
            // "상한 코드가 만들 수 있는 최악의 결함" 이라 부른 그것이다.
            //
            // 한때 "그 구간은 비어 있어 도달 불가" 로 두었는데 그 추론은 두 번 깨졌다: 특권 뷰 감사
            // 블록이 그 사이로 들어왔고(그래서 아래로 옮겼다), 아래 할당 자체도 OutOfMemoryError 로
            // 던질 수 있다. 추론으로 지키는 대신 <b>집행</b>한다 — 실패하면 자리를 되돌린다.
            // 세션마다 unicast Sink 1개(연결당 아웃바운드 1개). onBackpressureBuffer: 구독 전 emit·일시 적체 보관.
            // 버퍼는 유계 — 무제한이면 소비하지 않는 클라 하나가 Pod 힙을 잠식한다(초과 처리는 emit 참고).
            local.put(sessionId, pending);

            // ⚠️ 감사 로그가 <b>상한 검사 뒤</b>에 있다. 한때 register 첫 줄이었는데, 그러면 상한으로
            // 거부된 접속도 "특권 뷰 입장" 으로 남아 감사가 실제 특권 뷰보다 많아진다 — 이 줄의 값이
            // "누가 언제 어느 방을 봤는가" 에 답하는 것이라, 보지 못한 접속이 섞이면 그 답이 틀린다.
            // 관리자 입장만 남긴다. 이 세션은 방 주인과 같은 것을 받는다 — 마스킹 전 원문과 발신자
            // 이메일이다. 방 주인은 자기 방에 묶이지만 관리자는 진행 중인 아무 방이나 들어갈 수 있어
            // 범위 제한이 없고, 팬아웃에 기록을 붙이면 메시지마다 비용이 된다. 그래서 연결당 한 줄로
            // "누가 언제 어느 방을 봤는가"만 답할 수 있게 둔다. 그 이상은 이 자리의 몫이 아니다.
            // sessionId를 함께 남기는 건 탭 두 개를 가르고 이 파일의 종료·드롭 로그와 이어 붙이기 위해서다.
            // 문구가 '관리자'가 아닌 것은 판정이 역할을 가리지 않기 때문이다 — 특권 뷰가 넓어지는 날
            // 감사가 자동으로 따라오는 것이 이 설계의 값인데, 그때 비-관리자를 관리자로 기록하면 안 된다.
            // 그래서 role을 값으로 싣는다(오늘은 언제나 ADMIN이다).
            if (permissionPolicy.usesPrivilegedViewInSomeoneElsesRoom(session.role(), session.isRoomOwner())) {
                log.info("특권 뷰 입장 — role={} sessionId={} userId={} roomId={}",
                        session.role(), sessionId, session.userId(), session.roomId());
            }

            // compute — 집합 생성과 추가를 한 원자 구간에 묶는다. computeIfAbsent 후 add로 나누면 그 사이
            // 마지막 퇴장이 빈 집합을 걷어내 방금 넣은 세션이 인덱스에서 사라질 수 있다.
            roomSessions.compute(session.roomId(), (room, sessionIds) -> {
                Set<String> ids = sessionIds == null ? ConcurrentHashMap.newKeySet() : sessionIds;
                ids.add(sessionId);
                return ids;
            });
            // Redis 명부 등재 실패로 접속을 막지 않는다 — 이 HASH는 시청자 수 집계 전용이고, 메시지 전달·강퇴·
            // 종료는 전부 위 로컬 자료구조와 Pub/Sub으로 돈다. 강퇴 조회 실패에도 입장을 허용하는 정책과 같은 방향.
            //
            // 다만 이 관용이 실제로 값을 내는 건 <b>일시적·부분적</b> 실패일 때다. Redis가 통째로 죽으면 전달
            // 자체가 Pub/Sub이라 아무도 못 받는다. 그 구간의 전송은 강퇴·밴·종료 판정을 못 읽어 거절되므로
            // (SendChatService·EntryGate.isRoomAlive) 검사 없이 이력에 쌓이지 않는다.
            Mono<Void> registration = sessionRepository.add(session.roomId(), sessionId, session.userId())
                    .onErrorResume(e -> {
                        log.warn("세션 명부 등재 실패 — 접속은 진행(시청자 수만 부정확) roomId={} cause={}",
                                session.roomId(), e.getClass().getSimpleName());
                        return Mono.empty();
                    });
            registered = true;
            return registration;
        } finally {
            if (!registered) {
                // 자리만 되돌려서는 모자란다 — local 항목과 방 색인도 이미 들어가 있을 수 있고,
                // 등록이 던지면 그 뒤 단계가 돌지 않아 반쪽 상태가 남는다(호출부의 doFinally 는
                // 돌지만, 그것이 부르는 unregister 는 local.get 으로 키를 만들어 자리를 못 걷는다).
                //
                // ⚠️ 이 가드가 못 덮는 갈래가 하나 있다: 등록이 성공한 뒤 호출부의 다음 단계가
                // <b>영원히 끝나지 않는</b> 경우다(그러면 doFinally 가 돌지 않아 local 잔류가
                // 유계가 아니다). 실제로는 Lettuce 명령 타임아웃이 묶지만 그 값이 추적되는 설정에
                // 없어 라이브러리 기본값에 기댄다. 능력 증가는 없고(구독 전이라 프레임도 받지
                // 못한다) 자원 누수다 — 여기서 고칠 자리가 아니라 적어 둔다.
                // 자리는 <b>인자에서 키를 만들어 무조건</b> 되돌린다 — local 조회로 키를 만들면
                // 자리가 들어간 뒤 local.put 전에 던지는 구간에서 반납이 통째로 빠진다(실측).
                // 그 구간이 이 가드가 존재하는 유일한 이유다. 멱등이라 두 번 불려도 무해하다.
                releaseSlot(session.userId(), session.roomId(), sessionId);
                rollbackLocalIndexes(sessionId, session.roomId());
            }
        }
    }

    /**
     * transport 전용: 핸들러가 {@code session.send()} 에 연결할 아웃바운드 스트림. (포트 아님 — chat-core는 안 씀)
     *
     * <p>프레임을 그대로 흘린다 — 핸들러가 {@code frame.utf8(..)} 로 직렬화·인코딩을 한 번만 하게
     * 하려면 공유 객체가 소비 지점까지 와야 한다({@link OutboundFrame} 에 이유가 적혀 있다).
     */
    public Flux<OutboundFrame> outboundFrames(String sessionId) {
        LocalSession ls = local.get(sessionId);
        return ls == null ? Flux.empty() : ls.sink().asFlux();
    }

    /**
     * 같은 스트림을 메시지로만 본 것 — 와이어 모양에 관심이 없는 호출부·테스트용이다.
     *
     * <p>Sink 가 unicast 라 구독자는 하나뿐인데, 이건 <b>두 번째 구독이 아니라 매핑 뷰</b>다.
     * 구독 경로가 하나이므로 둘 중 어느 쪽을 쓰든 구독은 한 번만 일어난다.
     *
     * <p><b>package-private 이다.</b> 공개 구독 진입점은 {@link #outboundFrames(String)} 하나로 둔다 —
     * unicast sink 에 공개 진입점이 둘이면 "둘 다 구독하면 하나가 실패한다" 를 호출 관례로만 지키게
     * 되고, 그 관례는 모듈 밖에서 안 보인다. 이쪽은 같은 패키지 테스트가 와이어 모양에 관심 없을 때
     * 쓰는 편의다.
     */
    Flux<OutboundMessage> outbound(String sessionId) {
        return outboundFrames(sessionId).map(OutboundFrame::message);
    }

    @Override
    public Mono<Void> unregister(UUID roomId, String sessionId) {
        // 상한 인덱스를 <b>local 을 지우기 전에</b> 걷는다 — 세션 레코드에서 userId 를 읽어야 키를
        // 만들 수 있다. 먼저 지우면 키를 못 만들어 항목이 남고, 그 유저는 그 방에 다시 들어올 수
        // 없게 된다(상한이 영구히 찬 상태로 고정된다). 상한 코드가 만들 수 있는 최악의 결함이다.
        // 자리도 방 색인도 <b>세션 자신의 roomId</b> 로 걷는다 — 등재를 session.roomId() 로 했으므로
        // 그래야 같은 키다. 포트 계약({@code ChatSessionManager.unregister})에 인자와 세션의 방이
        // 같아야 한다는 보장이 없고, 어긋나는 호출이 들어오면 둘이 다 영구히 남는다.
        //
        // 방 색인 쪽 손실이 "순수 누수" 보다 나쁘다 — 죽은 sessionId 가 남은 방 항목이 존재하는 동안
        // pushViewerCount 의 자가치유(항목 부재 검사)가 발동하지 않아, 하트비트 회차마다 그 유령
        // 방에 Redis 왕복이 하나 영구히 붙는다(게이지와 캐시 둘도 같이 부푼다).
        //
        // 세션이 local 에 없으면 인자를 쓴다. 그 경우 방 색인에도 넣은 것이 없으므로(등록 순서가
        // local.put → roomSessions.compute 다) 어느 쪽을 써도 무연산이다 — 한때 "보상 경로에서
        // leaving 이 null 일 수 있어 인자가 필요하다" 를 제약으로 적었는데, 그 순서를 보면 제약이
        // 아니다.
        LocalSession leaving = local.get(sessionId);
        UUID indexRoom = leaving != null ? leaving.session().roomId() : roomId;
        if (leaving != null) {
            releaseSlot(leaving.session().userId(), leaving.session().roomId(), sessionId);
        }
        rollbackLocalIndexes(sessionId, indexRoom);
        // 로컬 정리는 끝났고, Redis 필드는 아래 HDEL이 지운다 — 그게 정상 회수 경로다.
        // 실패하면 방 종료의 clearRoom이, 그마저 놓치면 키 TTL이 받는다(3단 폴백).
        //
        // 호출부가 subscribe()라 에러를 그냥 두면 Reactor가 스택트레이스째 ERROR로 찍는다 — Redis가
        // 흔들리면 세션 수만큼 쏟아져 정작 원인 로그를 묻는다. 짚을 수 있는 형태로 한 줄만 남긴다.
        return sessionRepository.remove(roomId, sessionId)
                .onErrorResume(e -> {
                    log.warn("세션 명부 제거 실패 — 시청자 수 일시 과다 roomId={} sessionId={} cause={}",
                            roomId, sessionId, e.getClass().getSimpleName());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<Long> getActiveCount(UUID roomId) {
        CachedCount cached = activeCountCache.get(roomId);
        if (cached != null && System.nanoTime() - cached.expiresAtNanos() < 0) {
            return Mono.just(cached.value());
        }
        // defer: 합류 판단을 구독 시점에 한다. 즉시 평가하면 Mono 를 받아만 두고 구독을 늦추는
        // 호출자가 자기 시점의 비행에 끼지 못한다.
        return Mono.defer(() -> joinInFlightCount(roomId));
    }

    /**
     * 같은 방의 동시 조회를 <b>한 번의 왕복으로 합친다.</b>
     *
     * <p>캐시만으로는 메워지지 않는 창이 <b>셋</b> 있다 — ① 그 방의 최초 조회(캐시 부재: 재접속
     * 폭풍이 바로 이 경우) ② 캐시 만료 직후 1회 ③ {@code unregister} 가 빈 방 항목을 걷은 뒤
     * 재입장. 그 창에 도착한 호출은 전부 미스라 각자 왕복한다. 평상시에는 ms 짜리 창이라 중복이
     * 몇 건이지만, <b>파드 kill 후 재접속 폭풍</b>에서는 그 창에 그 방의 세션 수만큼이 들어온다.
     * 그러면 Redis 가 이미 부하를 받고 있는 순간에 HVALS 가 N 배로 간다.
     *
     * <p><b>그 N 배가 레이트리밋까지 끌어내린다.</b> 이 조회는 {@code HVALS} 로 방 해시 전체를
     * O(N) 으로 당겨오고 입장마다 불리는데 입장 엔드포인트에는 레이트리밋이 없다. 그만큼 느려지면
     * {@code RedisRateLimiter} 의 200ms 상한에 걸려 <b>그 Pod 의 레이트리밋 집행이 로컬 판정으로
     * 강등</b>된다. 실측으로 재 봤다 — 12,000 엔트리 해시에 동시 100건을 걸면 그 순간의 SETNX(당시 레이트리밋 호출)가
     * 211ms 로 상한을 넘는다. 그래서 이 병합은 "표시용 숫자 하나" 를 아끼는 것이 아니다.
     *
     * <p><b>그리고 병합이 그 경로를 닫는다 — 부분 완화가 아니다</b>(실측). 병합 뒤의 모양은 방마다
     * 1건이고, 총 세션 수를 방으로 어떻게 쪼개도 한 번에 당겨오는 바이트 총량은 같다. 설계 상한의
     * 5배(60,000 세션)를 한 방에 넣고 쟀을 때 28ms, 파드 6개가 방 100개를 동시에 조회하는 모양
     * (36만 엔트리)에서 130ms 로 둘 다 200ms 아래였다. <b>그래서 시청자 수를 유지 카운터로 바꾸는
     * 재설계는 근거가 없다</b> — 한때 여기에 그게 근본 해법이라고 적어 뒀는데 재 보니 틀렸다.
     *
     * <p>남는 꼬리의 <b>정체도 Redis 가 아니었다</b>(실측). 같은 {@code ReactiveStringRedisTemplate}
     * 이면 Lettuce 가 네이티브 커넥션 하나를 공유해서, 큰 {@code HVALS} 가 그 커넥션에서 뒤의
     * 명령을 막는다 — 같은 부하를 별도 커넥션 팩토리로 보내면 p99 가 113ms 에서 12ms 로 떨어졌다.
     * 즉 이 꼬리가 언제든 문제가 된다면 싼 해법은 이 조회에 전용 커넥션을 주는 쪽이고, 데이터
     * 모델을 바꿀 일이 아니다.
     *
     * <p>{@code cache()} 가 상류를 한 번만 구독하게 하고, {@code doFinally} 가 끝나는 즉시 항목을
     * 지워 다음 창이 새로 열린다. 취소에도 지워진다 — {@code cache()} 는 취소를 상류로 전파하지 않아
     * 상류가 끝까지 가고, 그 종료에서 {@code doFinally} 가 돈다.
     *
     * <p>실패를 캐시에 담지 않는다는 계약은 그대로다 — {@code activeCountCache} 는 값이 올 때만
     * 쓰이고, 에러는 같은 비행에 합류한 호출자들에게만 간다(그들은 같은 왕복을 기다린 사람들이다).
     * 다음 호출자는 새 비행을 연다.
     */
    private Mono<Long> joinInFlightCount(UUID roomId) {
        return inFlightCounts.computeIfAbsent(roomId, room -> {
            // 자기 자신을 제거 조건으로 쓰기 위해 참조를 들고 있는다. set 은 구독보다 먼저 끝난다 —
            // computeIfAbsent 가 돌려준 뒤에야 누군가 구독하고, doFinally 는 그 구독의 종료에서 돈다.
            AtomicReference<Mono<Long>> self = new AtomicReference<>();
            Mono<Long> flight = sessionRepository.count(room)
                    // HVALS distinct = 고유 유저 수.
                    // 캐시 쓰기가 cache() <b>상류</b>라 합류자가 50이어도 쓰기는 1회다 — 병합 전에는
                    // 호출자마다 각자 썼다.
                    .doOnNext(count -> cacheIfRoomStillLocal(room, count))
                    // ⚠️ <b>값까지 비교해서 지운다.</b> remove(room) 한 인자로 지우면 "내 비행이 끝났다"가
                    // 아니라 "그 방의 비행 자리를 비운다"가 되어, 그 사이에 설치된 <b>남의 비행</b>을
                    // 걷어낼 수 있다. 오늘은 cache() 가 doFinally 를 비행당 1회로 묶어 안전하지만,
                    // 그 안전이 연산자 선택 하나에 걸려 있다 — 제거를 cache() 하류로 옮기거나
                    // doFinally 와 cache() 사이에 retry 를 끼우면 구독자 수만큼 발화하고, 그러면
                    // 1회차가 내 항목을 지운 뒤 2회차가 새로 설치된 비행을 걷어낸다. 부하가 가장 높은
                    // 순간에 병합이 중복 왕복으로 역전된다. 두 인자 remove 는 맵의 값이 다르면
                    // 아무것도 지우지 않으므로 그 역전을 구조적으로 막는다.
                    .doFinally(signal -> inFlightCounts.remove(room, self.get()))
                    .cache();
            self.set(flight);
            return flight;
        });
    }

    /** 진행 중인 시청자 수 조회 수. (테스트 진입점 — 비행이 실제로 풀리는지 재는 데 쓴다) */
    int trackedInFlightCounts() {
        return inFlightCounts.size();
    }

    /**
     * 방별로 진행 중인 시청자 수 조회.
     *
     * <p>항목은 <b>구독에서 생기고 종료에서 지워진다.</b> 한때 "구독되지 않으면 지워지지 않는다"를
     * 전제로 적어 뒀는데 그 경로는 <b>존재하지 않는다</b> — {@code getActiveCount} 가
     * {@code Mono.defer} 라 조립만 하면 항목이 아예 생기지 않는다(실측: 조립 후 맵 크기 0).
     * 없는 누수를 경고하면 다음 사람이 그걸 걱정한다.
     *
     * <p>취소에서도 지워지지만 <b>취소 즉시가 아니다</b> — {@code cache()} 가 취소를 상류로 보내지
     * 않으므로 왕복이 끝날 때 {@code onComplete} 로 지워진다. 즉 취소된 조회도 남은 왕복 동안은
     * 병합 창을 열어 둔다.
     */
    private final ConcurrentHashMap<UUID, Mono<Long>> inFlightCounts = new ConcurrentHashMap<>();

    /**
     * 조회가 끝난 시점에도 그 방이 이 Pod에 남아 있을 때만 캐시에 담는다.
     *
     * <p>담는 시점과 회수 시점이 다른 락 구간이라 그냥 넣으면 샌다 — Redis를 다녀오는 수 ms 사이에
     * 마지막 세션이 끊기면 회수({@code unregister})가 먼저 지나가고 그 뒤에 항목이 새로 생긴다.
     * 그 방에 아무도 다시 안 들어오면 영영 남는다. 방 인덱스와 같은 락 안에서 판단해 그 창을 없앤다.
     */
    private void cacheIfRoomStillLocal(UUID roomId, long count) {
        roomSessions.computeIfPresent(roomId, (room, sessionIds) -> {
            activeCountCache.put(roomId, new CachedCount(count, System.nanoTime() + ACTIVE_COUNT_CACHE_NANOS));
            return sessionIds;
        });
    }

    @Override
    public Mono<Void> sendToSession(String sessionId, OutboundMessage message) {
        return Mono.fromRunnable(() -> emit(local.get(sessionId), new OutboundFrame(message)));
    }

    @Override
    public Mono<Void> sendToRoomLocal(UUID roomId, OutboundMessage message) {
        return Mono.fromRunnable(() -> {
            FanOutBudget budget = newSharedBudget();
            // 전원이 같은 메시지를 받는다 — 프레임 하나를 공유하므로 직렬화도 한 번이다.
            OutboundFrame frame = new OutboundFrame(message);
            forEachInRoom(roomId, ls -> emit(ls, frame, budget));
        });
    }

    @Override
    public Mono<Void> sendToRoomGated(UUID roomId, Function<ChatSession, OutboundMessage> resolver) {
        // 세션별 차등 fan-out — resolver가 세션마다 메시지 생성(방주인 PII 게이팅·kick 분기). null이면 그 세션 skip.
        return Mono.fromRunnable(() -> {
            FanOutBudget budget = newSharedBudget();
            // resolver 가 돌려주는 서로 다른 메시지마다 프레임 하나 — 같은 메시지를 받는 세션들은
            // 그 프레임을 공유하므로 직렬화가 세션 수가 아니라 <b>종류 수</b>에 비례한다.
            // 동치가 아니라 동일성으로 묶는다: fanOutChat 이 뷰 4종을 미리 만들어 재사용하므로
            // 동일성으로 충분하고, record equals 는 15 필드를 비교하므로 이쪽이 싸다.
            //
            // ⚠️ "빗나가도 직렬화 한 번 더 하는 것뿐" 이라고 적었는데 <b>틀렸다</b>. 이 맵은 위
            // STALE 예산 javadoc 이 "예산 소비자를 더 붙이지 말라" 고 못 박은 바로 그 루프 안에 있다 —
            // 12,000 세션 fan-out 이 이미 예산의 79%(3.96ms/5ms)다. 계약이 지켜지면 엔트리 4개라
            // 무시할 값이지만, 빗나가면 12,000 distinct 엔트리(table 65,536 · refs 약 524KB)로
            // 실측 +0.94ms 가 그 위에 얹힌다. 그때 꼬리 세션은 재시도 없이 한 번만 시도하고,
            // 결과는 조용한 유실이 아니라 metrics.deliveryDropped(SESSION_EMIT_CONTENTION) 로
            // 세어지는 드롭이다(세션은 끊기지 않는다 — consumerCannotKeepUp 이 그 결과를 안 본다).
            // 그래서 계약은 javadoc 이 아니라 테스트가 지킨다
            // (ChatBroadcastSubscriberTest.theResolverReturnsTheSameInstancePerView).
            Map<OutboundMessage, OutboundFrame> frames = new IdentityHashMap<>(4);
            forEachInRoom(roomId, ls -> {
                OutboundMessage message = resolver.apply(ls.session());
                if (message != null) {
                    emit(ls, frames.computeIfAbsent(message, OutboundFrame::new), budget);
                }
            });
        });
    }

    @Override
    public Mono<Void> closeUser(UUID roomId, UUID userId) {
        // 강퇴는 정책상 종료라 1008 — 프론트가 "재접속하지 말 것"으로 읽는 코드다.
        return Mono.fromRunnable(() -> {
            FanOutBudget budget = newSharedBudget();
            forEachInRoom(roomId, ls -> {
                if (ls.session().userId().equals(userId)) {
                    metrics.sessionCut(SessionCutReason.MODERATION);
                    terminate(ls, CloseStatus.POLICY_VIOLATION, budget.completeDeadline());
                }
            });
        });
    }

    @Override
    public Mono<Void> sendToUserEverywhere(UUID userId, OutboundMessage message) {
        return Mono.fromRunnable(() -> {
            FanOutBudget budget = newSharedBudget();
            // 한 사람의 모든 탭이 같은 메시지를 받는다 — 위와 같은 이유로 프레임 하나를 공유한다.
            OutboundFrame frame = new OutboundFrame(message);
            forEachSessionOf(userId, ls -> emit(ls, frame, budget));
        });
    }

    @Override
    public Mono<Void> closeUserEverywhere(UUID userId, SessionCutReason reason) {
        // 밴도 탈퇴도 1008 이다 — 프론트가 "재접속하지 말 것"으로 읽는다. 와이어가 같으니 둘을
        // 가르는 것은 사유 태그뿐이고, 그래서 호출자가 그것을 넘긴다(제재율과 탈퇴율은 다른 수다).
        return Mono.fromRunnable(() -> {
            FanOutBudget budget = newSharedBudget();
            forEachSessionOf(userId, ls -> {
                metrics.sessionCut(reason);
                terminate(ls, CloseStatus.POLICY_VIOLATION, budget.completeDeadline());
            });
        });
    }

    /**
     * 이 Pod에서 그 사람의 세션을 전부 훑는다.
     *
     * <p><b>방 색인을 못 쓴다</b> — 계정 조치는 방을 모른 채로 오고, {@code userId → 세션} 색인은 두지
     * 않았다. 두면 {@code register}/{@code unregister}마다 동기화 대상이 하나 늘고 그 어긋남이 조용한
     * 버그가 되는데, 그 비용을 낼 만큼 이 경로가 잦지 않다.
     *
     * <p>⚠️ <b>{@link #userRoomSessions} 가 생겼지만 이 경로는 여전히 훑는다.</b> 그 색인의 키는
     * {@code (userId, roomId)} 쌍이라 "이 사람의 세션 전부" 를 물으면 방을 모르는 채로 전체 항목을
     * 뒤져야 한다 — 이 스캔보다 싸지 않다. 위 문단의 경고가 틀렸다는 뜻도 아니다: 그 색인에
     * 정확히 그 조용한 버그가 생길 수 있고(퇴장이 항목을 걷지 않으면 그 유저가 그 방에 영구히
     * 못 들어온다), 그래서 {@code SessionAdmissionCapTest} 가 그 한 가지를 따로 고정한다. 색인을
     * 하나 더 두는 값은 <b>메시지마다 도는 경로</b>에서만 나오고, 이 경로는 시간당 몇 건이다.
     * 12,000 세션 전체 스캔이 58µs다(실측, 워밍업 후 최소값).
     *
     * <p><b>비용을 폭발시키는 변수는 이벤트 빈도가 아니라 파드당 세션 수다.</b> 스캔은 선형이 아니다 —
     * 12,000에서 58µs인데 100,000에서 4.2ms로, 8.3배 입력에 70배 넘게 든다(캐시 미스). 시간당 한 건이어도
     * 10만 세션이면 한 건이 스레드를 그만큼 잡는다.
     *
     * <p>그리고 <b>이벤트 하나가 스캔을 두 번</b> 한다(사유 전송 + 종료). 위 수치는 1회분이므로 실제
     * 비용은 그 두 배다 — 10만이면 8ms 남짓이다. <b>파드당 2~3만 세션</b>이 다시 판단할 자리다.
     *
     * <p>돌 때 밟는 것은 이벤트루프가 아니라 Redis pub/sub I/O 스레드다({@code listenToChannel}이 자기
     * 연결을 만든다). 시청자 커넥션이 멈추지는 않지만 한가한 스레드도 아니다 — 방 팬아웃과 커맨드 응답이
     * 같은 곳에 얹힌다.
     */
    private void forEachSessionOf(UUID userId, Consumer<LocalSession> action) {
        for (LocalSession ls : local.values()) {
            if (ls.session().userId().equals(userId)) {
                action.accept(ls);
            }
        }
    }

    @Override
    public Mono<Void> closeAll(UUID roomId) {
        // 방 종료는 정상 종료(1000). 사유는 앞서 보낸 SYSTEM(ROOM_ENDED)이 전달한다.
        return Mono.fromRunnable(() -> {
            FanOutBudget budget = newSharedBudget();
            forEachInRoom(roomId, ls -> {
                metrics.sessionCut(SessionCutReason.ROOM_ENDED);
                terminate(ls, CloseStatus.NORMAL, budget.completeDeadline());
            });
        });
    }

    /** transport 전용: 핸들러가 강제 종료 경로에 연결할 제어 신호. 모르는 세션이면 영영 발화하지 않는다. */
    public Mono<CloseStatus> terminationSignal(String sessionId) {
        LocalSession ls = local.get(sessionId);
        // 빈 Mono를 주면 즉시 완료돼 멀쩡한 세션이 곧장 닫힌다 — never여야 한다.
        return ls == null ? Mono.never() : ls.terminate().asMono();
    }

    /**
     * transport 전용: 서버가 이미 이 세션의 종료를 확정했는지.
     *
     * <p>종료 확정 후에도 소켓이 닫히기 전까지 짧은 창이 남는다(정상 클라는 ms, 소켓을 읽지 않는 클라는
     * 유예 시간만큼). 그 창에 들어오는 전송을 막지 않으면 강퇴된 사용자가 계속 보낼 수 있다.
     */
    public boolean isTerminating(String sessionId) {
        LocalSession ls = local.get(sessionId);
        return ls != null && ls.closeStatus().get() != null;
    }

    /**
     * transport 전용: 이 세션이 쓰던 방이 <b>이미 끝난 것으로 확인됐는지</b>.
     *
     * <p>{@link #shouldRecheckRoomAlive}보다 <b>먼저</b> 봐야 한다. 창만으로 두면 종료를 확인한 직후부터
     * 다음 확인까지의 프레임이 전부 통과해 끝난 방에 그대로 쌓인다 — 창 간격마다 한 건만 막는 꼴이 된다.
     *
     * @return 종료가 확인된 세션이면 true. 그 세션은 더 물을 것 없이 전송을 막는다.
     */
    public boolean isRoomKnownEnded(String sessionId) {
        LocalSession ls = local.get(sessionId);
        return ls != null && ls.roomEnded().get();
    }

    /**
     * transport 전용: 이 세션이 쓰던 방이 끝났다는 사실을 적어둔다.
     *
     * <p><b>되돌리는 경로가 없다.</b> 끝난 방은 다시 라이브가 되지 않으므로(live 상태머신에서 Ended는
     * 종착 상태다) 한 번 확인한 종료는 이 세션이 끝날 때까지 유효하다. 창으로만 두면 확인한 직후부터
     * 다음 확인까지의 프레임이 그대로 통과해 이력에 쌓인다 — 막으려던 것이 거의 그대로 남는다.
     */
    public void markRoomEnded(String sessionId) {
        LocalSession ls = local.get(sessionId);
        if (ls != null) {
            ls.roomEnded().set(true);
        }
    }

    /**
     * transport 전용: 이 세션이 쓰는 방이 아직 살아있는지 <b>다시 물어봐야 하는지</b>.
     *
     * <p>종료 신호는 Pub/Sub이라 그 순간 구독이 끊겨 있던 Pod는 통째로 놓친다. 놓치면 그 Pod의 세션은
     * 닫히지 않고, 끝난 방에 계속 글이 쌓인다 — 그리고 그 글은 Mongo에 남는다. 입장 때 한 번 본 것으로는
     * 이 구간이 유계가 아니라서, 전송 경로에서 간격을 두고 다시 확인한다.
     *
     * <p>간격을 두는 이유는 비용이다. 매 프레임 물으면 정상 전송마다 Redis 왕복이 하나씩 더 붙는데,
     * 여기서 얻으려는 건 실시간성이 아니라 <b>구간의 상한</b>이다.
     *
     * <p><b>상한은 창 길이가 아니라 {@code min(창 길이, 마커 잔여수명)}이다.</b> 마커에는 TTL이 있어서,
     * 종료 후 그 시간이 지나도록 한 프레임도 안 보낸 세션은 다시 보낼 때 마커를 못 찾고 통과한다.
     * 그 세션은 이후 상한 없이 끝난 방에 쓴다 — 마커 수명을 넘기는 조용한 세션은 이 방어가 닿지 않는다.
     *
     * @return 지금 확인해야 하면 true. false면 호출자는 묻지 않고 살아있는 것으로 본다.
     *         이미 종료가 확인된 세션은 {@link #isRoomKnownEnded}가 앞에서 걸러야 한다.
     */
    public boolean shouldRecheckRoomAlive(String sessionId) {
        LocalSession ls = local.get(sessionId);
        if (ls == null) {
            return false;
        }
        long now = System.nanoTime();
        long last = ls.lastRoomAliveCheckNanos().get();
        // CAS로 한 번만 통과시킨다 — 통과한 쪽만 실제로 조회하고, 나머지는 그 결과를 기다리지 않고 지나간다.
        return now - last >= ROOM_ALIVE_RECHECK_INTERVAL_NANOS
                && ls.lastRoomAliveCheckNanos().compareAndSet(last, now);
    }

    /**
     * transport 전용: 다음 프레임이 방 종료를 <b>다시 묻게</b> 한다. 확인이 실패했을 때 쓴다 —
     * {@link #shouldRecheckRoomAlive} 가 통과시킨 순간 창을 소비하므로, 되돌리지 않으면 실패한 확인이
     * 다음 창 길이만큼 확인한 것으로 남는다.
     */
    public void requireRoomAliveRecheck(String sessionId) {
        LocalSession ls = local.get(sessionId);
        if (ls != null) {
            ls.lastRoomAliveCheckNanos().set(System.nanoTime() - ROOM_ALIVE_RECHECK_INTERVAL_NANOS);
        }
    }

    /**
     * transport 전용: 이 세션에서 프레임이 올라왔다는 사실을 적어 둔다 — 종류는 묻지 않는다.
     *
     * <p><b>왜 종류를 묻지 않는가.</b> 재려는 것은 "클라가 말을 하는가"가 아니라 <b>소켓이 아직 살아
     * 있는가</b>다. 채팅은 대다수가 보지만 말하지 않는 구조라 발화를 생존 신호로 쓰면 정상 시청자가
     * 전부 죽은 것으로 보인다. 그래서 서버가 주기적으로 ping 을 보내고, 그 응답(pong)도 다른 프레임과
     * 똑같이 여기로 들어와 시계를 민다.
     *
     * <p>클라가 먼저 보내는 ping 은 여기 오지 않는다 — Netty 가 자동으로 pong 을 돌려주고 올려주지
     * 않는다(인바운드 필터 주석 참고). 그래서 서버가 먼저 묻는 쪽이어야 한다.
     */
    public void recordInbound(String sessionId) {
        LocalSession ls = local.get(sessionId);
        if (ls != null) {
            ls.lastInboundNanos().set(System.nanoTime());
        }
    }

    /**
     * transport 전용: 이 세션이 상한 동안 아무 프레임도 올리지 않았는가.
     *
     * <p>모르는 세션은 <b>거짓</b>이다 — 이미 사라진 세션을 "응답 없음"으로 답하면 호출자가 없는
     * 세션을 또 닫으려 든다.
     */
    public boolean isUnresponsive(String sessionId, Duration deadline) {
        LocalSession ls = local.get(sessionId);
        if (ls == null) {
            return false;
        }
        return System.nanoTime() - ls.lastInboundNanos().get() >= deadline.toNanos();
    }

    /** 생존 시계를 과거로 밀어 "응답 없음" 상태를 만든다. (테스트 진입점 — 기한을 기다리지 않기 위해) */
    void expireLiveness(String sessionId, Duration deadline) {
        LocalSession ls = local.get(sessionId);
        if (ls != null) {
            ls.lastInboundNanos().addAndGet(-deadline.toNanos());
        }
    }

    /** 마지막 확인 시각을 과거로 밀어 창이 열린 상태를 만든다. (테스트 진입점 — 30초를 기다리지 않기 위해) */
    void expireRoomAliveWindow(String sessionId) {
        LocalSession ls = local.get(sessionId);
        if (ls != null) {
            ls.lastRoomAliveCheckNanos().addAndGet(-ROOM_ALIVE_RECHECK_INTERVAL_NANOS);
        }
    }

    /**
     * transport 전용: 지금 이 세션에 거부 응답을 되돌려줘도 되는가.
     *
     * <p>사유를 가리지 않는다 — 무엇을 예외로 두든 그게 곧 우회로가 되기 때문이다. 간격 안의 거부는
     * 조용히 버린다(연결은 유지). 되돌림 비용이 0이 되므로 반복해 밀어넣을 이유가 사라진다.
     *
     * @return 답해도 되면 true. false면 호출자는 아무것도 보내지 않는다.
     */
    public boolean shouldReplyToRejection(String sessionId) {
        LocalSession ls = local.get(sessionId);
        if (ls == null) {
            return false;   // 이미 사라진 세션 — 되돌려 보낼 곳이 없다
        }
        long now = System.nanoTime();
        long last = ls.lastRejectionReplyNanos().get();
        return now - last >= REJECTION_REPLY_INTERVAL_NANOS
                && ls.lastRejectionReplyNanos().compareAndSet(last, now);
    }

    /**
     * transport 전용: 이 프레임을 읽지 않고 버려야 하는가 — 세션당 인바운드 상한을 넘었으면 true.
     *
     * <p>버린 프레임에는 아무것도 답하지 않는다. 답하면 그 응답이 다시 비용이 되고, 상한을 넘는 속도는
     * 정상 클라가 내지 않으므로 알려 줄 상대도 없다. 연결은 끊지 않는다.
     *
     * <p>모르는 세션은 버리지 않는다 — 상한은 등록된 세션의 상태라 셀 것이 없다.
     */
    public boolean shouldDropInbound(String sessionId) {
        LocalSession ls = local.get(sessionId);
        return ls != null && !admitInbound(ls.inboundAllowedAtNanos(), System.nanoTime());
    }

    /**
     * GCRA — {@code allowedAt} 은 받은 프레임을 간격 하나씩 줄 세웠을 때 그 줄이 끝나는 시각이다. 받을 때마다
     * 간격 하나씩 민다. 줄 끝이 지금보다 {@code (버스트 - 1) × 간격} 넘게 앞서 있으면 버스트를 다 쓴 것이다.
     * 줄 끝이 지나간 과거면 지금부터 다시 세운다 — 쉬는 동안 몫이 버스트보다 많이 쌓이지 않는 이유다.
     *
     * <p>읽고 쓰기가 원자적이지 않다. 호출자는 한 세션의 수신 {@code Flux} 위의 {@code filter} 하나라
     * ({@code ChatWebSocketHandler.runInbound}) 같은 세션에서 겹쳐 불리지 않는다 — Reactive Streams 는
     * {@code onNext} 를 직렬로 부른다.
     * nanoTime 은 원점이 임의라 크기 비교 대신 차이의 부호로 비교한다.
     */
    static boolean admitInbound(AtomicLong allowedAt, long now) {
        long at = allowedAt.get();
        long base = at - now > 0 ? at : now;
        if (base - now > (INBOUND_FRAME_BURST - 1) * INBOUND_FRAME_INTERVAL_NANOS) {
            return false;
        }
        allowedAt.set(base + INBOUND_FRAME_INTERVAL_NANOS);
        return true;
    }

    /**
     * transport 전용: 강퇴가 확인된 세션을 끊는다.
     *
     * <p>전송 경로에서 강퇴가 확인됐다는 건 <b>이 Pod가 강퇴 신호를 못 받았다</b>는 뜻이다 — 받았다면
     * 이미 닫혀서 여기까지 오지 않는다. 강퇴 전달도 무영속 Pub/Sub이라 놓친 Pod가 생길 수 있고, 그때
     * 그 세션은 방 메시지를 계속 읽으면서 프레임마다 강퇴 조회를 태운다(그 조회는 레이트리밋보다 앞이라
     * 유계가 아니다). 방금 권위 있게 확인했으니 여기서 닫는다.
     */
    public void terminateKicked(String sessionId) {
        LocalSession ls = local.get(sessionId);
        if (ls == null) {
            return;
        }
        log.warn("전송 경로에서 강퇴 확인 — 세션 종료(종료 신호를 놓친 Pod) sessionId={} roomId={} userId={}",
                sessionId, ls.session().roomId(), ls.session().userId());
        metrics.sessionCut(SessionCutReason.MODERATION);
        terminate(ls, CloseStatus.POLICY_VIOLATION, newBudget().completeDeadline());
    }

    /**
     * transport 전용: 방이 끝난 것으로 확인된 세션을 닫는다.
     *
     * <p>정상 종료(1000)로 닫는다 — {@link #closeAll}과 같은 코드다. 이 경로는 종료 신호를 놓친 Pod가
     * 뒤늦게 같은 사실을 알게 된 것뿐이라, 클라이언트 입장에서 정상 종료와 달라야 할 이유가 없다.
     * 사유는 앞서 보낸 SYSTEM(ROOM_ENDED)이 전달한다(정상 경로와 동일).
     */
    public void terminateRoomEnded(String sessionId) {
        LocalSession ls = local.get(sessionId);
        if (ls == null) {
            return;
        }
        metrics.sessionCut(SessionCutReason.ROOM_ENDED);
        log.warn("전송 경로에서 방 종료 확인 — 세션 종료(종료 신호를 놓친 Pod) sessionId={} roomId={}",
                sessionId, ls.session().roomId());
        terminate(ls, CloseStatus.NORMAL, newBudget().completeDeadline());
    }

    /**
     * transport 전용: 수명 상한에 닿은 세션을 닫는다 — 재접속으로 권위를 다시 받게 하려는 것이다.
     *
     * <p><b>왜 수명에 상한이 필요한가.</b> 입장 토큰은 핸드셰이크 때 <b>한 번만</b> 검증되고 그 안에
     * 신원·역할·방 소유가 들어 있다. 그 뒤로 계정 쪽에서 무엇이 바뀌어도 이 세션은 모른다 —
     * 역할 강등, 방 소유권 이전, 탈퇴 확정. 지금 그 중 전파되는 것은 밴 하나뿐(계정 이벤트 채널)이고
     * 나머지는 세션이 살아 있는 동안 영원히 옛 권위로 돈다.
     *
     * <p>개별 전파를 하나씩 늘리는 대신 <b>수명으로 유계로 만든다.</b> 재접속은 live의 입장을 다시
     * 타므로 <b>방 상태</b>는 거기서 새로 평가된다 — 끝난 방이면 토큰이 아예 발급되지 않고 소유권
     * 이전도 반영된다. 그 둘은 live DB 에서 온다.
     *
     * <p><b>계정 상태는 축이 갈린다.</b> 역할은 live DB 가 아니라 <b>access 토큰 claims</b> 에서 온다.
     * 탈퇴·로그아웃은 세션을 폐기하고({@code JwtTokenLifecycle.revoke}/{@code revokeAll}) live-app 의
     * {@code TokenRevocationFilter} 가 그것을 조회하므로, 그 사람은 재접속에서 <b>새</b> 토큰을 받지 못한다.
     * 다만 <b>이미 받아 둔</b> 룸 토큰은 만료까지 핸드셰이크를 지나므로, 로그아웃의 반영 상한은 "그 토큰의 잔여
     * TTL 안에 다시 들어온 세션의 수명" 까지다. 한때 여기에 "반영 상한이 이 수명 하나로 줄었다" 고 적혀 있었는데 받아 둔 토큰을
     * 빠뜨린 문장이었고, 그 전에는 "이 수명 + access 잔여 TTL" 이었다(live-app 이 폐기를 보지 않던 때).
     *
     * <p>⚠️ <b>역할 강등은 다르다.</b> 그 경로가 토큰을 폐기하지 않으면 폐기 조회가 집지 못한다 —
     * 지금은 역할을 바꾸는 쓰기 경로가 이 저장소에 아예 없다({@code UserEntity} 정적 팩터리 둘뿐).
     * 특권 세션(방 주인·ADMIN)은 이 수명 동안 {@code senderEmail} 과 마스킹 전 원문을 계속 받으므로
     * 그건 PII 창이다 — 짧은 별도 상한이나 밴과 같은 전파 채널이 그쪽의 처방이고, 이 수명이
     * 대신하지 못한다.
     *
     * <p>정상 종료(1000)로 닫는다. 이 코드에는 "곧장 다시 붙어라"는 뜻이 이미 실려 있고
     * ({@link #emit}의 1013 주석 참고) 여기서 원하는 것이 바로 그것이다. 사유 프레임은 보내지 않는다 —
     * 클라가 해야 할 일이 재접속 하나뿐이라 알려 줄 것이 없고, 새 SYSTEM 코드를 만들면 프론트 계약이
     * 늘어난다.
     */
    public void terminateLifetimeExpired(String sessionId) {
        LocalSession ls = local.get(sessionId);
        if (ls == null) {
            return;
        }
        // 솎아낸다. 같이 들어온 세션들이 같이 만료되므로 이 로그는 재접속 폭주 구간에 몰리고,
        // 그 구간에 세션당 한 줄을 남기면 원인 로그를 제 로그로 묻는다 — 이 파일의 다른 열화 로그가
        // 전부 간격 솎기를 쓰는 근거와 같다. 누적 수는 함께 남겨 규모를 잃지 않는다.
        metrics.sessionCut(SessionCutReason.LIFETIME);
        long expired = lifetimeExpiredCount.incrementAndGet();
        if (shouldLogLifetimeExpiry()) {
            log.info("세션 수명 상한 — 종료(재접속으로 권위 재평가) 표본 sessionId={} 표본 roomId={} 누적={}",
                    sessionId, ls.session().roomId(), expired);
        }
        terminate(ls, CloseStatus.NORMAL, newBudget().completeDeadline());
    }

    /**
     * transport 전용: 기한 동안 아무 프레임도 올리지 않은 세션을 끊는다.
     *
     * <p>이것이 거두는 것은 <b>죽은 소켓</b>이다. 아무것도 <i>보내지</i> 않는 클라는 정상이지만
     * (시청만 하는 경우가 대다수다) 서버 ping 에도 아무것도 <i>올리지</i> 않는 클라는 이미 끊긴 소켓과
     * 구별되지 않는다. 그런 세션을 거두지 않으면 sink·방 인덱스·Redis 명부가 수명 상한까지 남는다.
     *
     * <p>정상 종료(1000)다 — 클라가 살아 있다면 다시 붙으면 되고, 죽었다면 받을 쪽이 없다.
     */
    public void terminateUnresponsive(String sessionId) {
        LocalSession ls = local.get(sessionId);
        if (ls == null) {
            return;
        }
        metrics.sessionCut(SessionCutReason.LIVENESS);
        log.info("생존 확인 실패 — 세션 종료(응답 없는 소켓 회수) sessionId={} roomId={}",
                sessionId, ls.session().roomId());
        terminate(ls, CloseStatus.NORMAL, newBudget().completeDeadline());
    }

    /** transport 전용: 이 세션을 어떤 코드로 닫을지. 서버가 정한 사유가 없으면(클라가 먼저 끊는 등) 정상 종료. */
    public CloseStatus closeStatusOf(String sessionId) {
        LocalSession ls = local.get(sessionId);
        CloseStatus status = ls == null ? null : ls.closeStatus().get();
        return status == null ? CloseStatus.NORMAL : status;
    }

    /**
     * 세션 종료 — 사유를 기록하고, 데이터 채널(complete)과 제어 채널 양쪽에 알린다.
     *
     * <p>둘 다 거는 이유: complete는 버퍼에 남은 메시지를 먼저 흘려보낸 뒤 곱게 닫는 정상 경로다
     * (강퇴 직전에 보낸 SYSTEM(KICKED)이 이 덕에 전달된다). 제어 채널은 그게 막혔을 때 —
     * 클라가 소켓을 읽지 않아 버퍼가 비지 않을 때 — 자원을 회수하는 확실한 경로다.
     */
    private void terminate(LocalSession ls, CloseStatus status, long deadline) {
        // 이미 종료 중이면 그때 정해진 사유를 유지한다 — 제어 채널(Sinks.One)도 첫 값만 받으므로,
        // 저장된 사유와 실제로 흘러간 사유가 어긋나지 않게 여기서 한 번만 확정한다.
        //
        // 뒤늦은 강퇴가 앞선 종료 사유를 덮지 못한다는 뜻이기도 하다(버퍼 초과 직후 강퇴가 오면 1013으로 닫힌다).
        // 덮게 만들려면 제어 채널을 다시 발화해야 하는데 Sinks.One은 못 하고, 억지로 하면 위 불변식이 깨진다.
        // 실질 피해도 없다 — 어느 코드로 닫히든 재접속은 입장 게이트의 강퇴 조회에서 다시 막힌다.
        if (!ls.closeStatus().compareAndSet(null, status)) {
            return;
        }
        complete(ls, deadline);
        // 제어 채널은 데이터 채널이 막혔을 때의 최후 경로다 — 실패하면 그 세션은 강제 종료 수단을 잃으므로 남긴다.
        //
        // ⚠️ <b>아래 실패 분기는 현재 코드에서 도달 불가다.</b> Sinks.one()은 SinkOneSerialized 이고 첫
        // tryEmitValue 는 구독자가 없어도, 구독자가 취소한 뒤에도 OK 를 돌려준다(실측). 실패는 2회차
        // (FAIL_TERMINATED)와 동시 발화(FAIL_NON_SERIALIZED)뿐인데, 위 closeStatus CAS 가 발화를
        // 1회·1스레드로 고정하고 이 메서드가 유일한 호출지다. 지우지 않는 이유는 그 두 전제(CAS 와
        // 단일 호출지) 중 하나만 깨져도 곧 도달하게 되고, 그때 잃는 것이 "그 세션의 강제 종료 수단"이라
        // 조용히 넘어가서는 안 되기 때문이다. <b>이 로그가 보이면 그 전제를 먼저 확인할 것.</b>
        Sinks.EmitResult signalled = ls.terminate().tryEmitValue(status);
        if (signalled.isFailure()) {
            log.error("세션 종료 제어 신호 발화 실패 — 강제 종료 경로 상실 sessionId={} roomId={} result={}",
                    ls.sessionId(), ls.session().roomId(), signalled);
        }
    }

    /**
     * 방 인덱스를 타고 해당 방 세션만 순회한다. Pod 전체 스캔을 피하는 자리라 방 fan-out은 모두 여기를 통한다.
     *
     * <p>인덱스에는 있지만 {@link #local}에서 이미 빠진 세션은 건너뛴다 — 퇴장 중인 세션과 겹칠 수 있고,
     * 그 세션은 어차피 곧 닫힌다.
     */
    private void forEachInRoom(UUID roomId, Consumer<LocalSession> action) {
        Set<String> sessionIds = roomSessions.get(roomId);
        if (sessionIds == null) {
            return;
        }
        for (String sessionId : sessionIds) {
            LocalSession ls = local.get(sessionId);
            if (ls != null) {
                action.accept(ls);
            }
        }
    }

    /** 인덱스가 추적 중인 방 수. (테스트 진입점 — 방이 비면 항목이 사라지는지 확인용) */
    int trackedRoomCount() {
        return roomSessions.size();
    }

    /**
     * 이 Pod 가 지금 들고 있는 세션 수.
     *
     * <p>{@code public} 인 이유는 게이지 배선이 쓰기 때문이다({@code ChatSessionGaugeConfig}).
     * 이 수가 보이지 않으면 "파드 하나가 몇 세션까지 버티는가" 를 물을 수 없고, 그러면 <b>파드 용량
     * 상한</b>을 고를 근거가 없다 — 그래서 그 상한보다 이 게이지가 먼저다. 남용 상한
     * ({@link #maxSessionsPerUserPerRoom()})은 이 수를 기다리지 않았다: 그쪽 값은 용량이 아니라
     * "정상 사용자가 동시에 몇 개를 여는가" 에서 나온다. 한때 이 문장이 "연결 수 상한" 으로 적혀
     * 그 둘을 한 덩이로 묶었다.
     */
    public int localSessionCount() {
        return local.size();
    }

    /** 이 Pod 가 중계 중인 방 수 — 게이지용. 세션 수와 함께 봐야 "방당 세션" 이 나온다. */
    public int localRoomCount() {
        return roomSessions.size();
    }

    /**
     * 세션 Sink로 1건 밀어넣기. null 세션은 무시.
     *
     * <p>실패 종류에 따라 처분이 다르다. 버퍼가 찼다는 건 <b>그 클라가 못 따라온다</b>는 뜻이라 세션을 끊고,
     * 경합 소진은 <b>다른 스레드가 그 순간 락을 쥐고 있었다</b>는 뜻일 뿐 세션은 멀쩡하므로 그 메시지만 버린다.
     * 둘을 같이 묶으면 부하가 오를수록(=소진이 잦아질수록) 멀쩡한 시청자를 끊게 되고, 끊긴 클라의 재접속이
     * 부하를 더 올려 스스로 번진다.
     *
     * <p>아웃바운드 종료 신호는 버퍼에 쌓인 메시지 뒤에 줄을 서므로 소켓을 읽지 않는 클라에겐 닿지 않는다.
     * 그 경우를 위해 {@code terminate}가 별도 제어 채널을 함께 발화한다(핸들러가 그걸로 연결을 끊는다).
     */
    private void emit(LocalSession ls, OutboundFrame frame, FanOutBudget budget) {
        if (ls == null) {
            return;
        }
        Sinks.EmitResult result = emitSerially(() -> ls.sink().tryEmitNext(frame), budget.emitDeadline());
        if (consumerCannotKeepUp(result)) {
            // 1013(SERVICE_OVERLOAD) — 정상 종료(1000)로 닫으면 프론트가 곧장 다시 붙는다. 버퍼가 넘친 건
            // 그 클라가 못 따라온다는 뜻이라, 즉시 재접속은 같은 결과를 부르고 부하 상황일수록 스스로 번진다.
            // 재시도 자체는 의미가 있으므로 금지(1008)가 아니라 "나중에"로 알린다 — 간격은 프론트 backoff가 정한다.
            log.warn("아웃바운드 버퍼 초과 — 세션 종료 sessionId={} roomId={} userId={} result={}",
                    ls.sessionId(), ls.session().roomId(), ls.session().userId(), result);
            // complete에 별도 예산을 주지 않는다(이 fan-out의 emitDeadline을 그대로 넘긴다). complete는 "버퍼에 남은 걸
            // 흘려보낸 뒤 곱게 닫는" 경로인데, 여기 온 이유가 바로 그 버퍼가 꽉 차서 클라가 안 읽는다는
            // 것이라 흘려보낼 곳이 없다. 그 무의미한 일에 최대 45ms를 쓰면 — 그것도 이 Pod의 모든 방을
            // 중계하는 Redis 구독 스레드 위에서 — fan-out 예산이 그만큼 지나가 뒤쪽 세션들이 재시도
            // 없이 한 번만 시도하게 된다. 종료 자체는 제어 채널이 보장하므로 잃는 것도 없다.
            metrics.sessionCut(SessionCutReason.BUFFER_OVERFLOW);
            terminate(ls, CloseStatus.SERVICE_OVERLOAD, budget.emitDeadline());
        } else if (result == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
            // 세션은 살려둔다. 대신 드롭을 세어 "조용한 유실"이 되지 않게 한다.
            // 로그는 솎아낸다 — 드롭이 나는 상황이 곧 CPU 포화라, 건당 동기 로그가 그걸 더 악화시킨다.
            metrics.deliveryDropped(DeliveryStage.SESSION_EMIT_CONTENTION);
            long dropped = droppedOnContention.incrementAndGet();
            if (shouldLogContentionDrop()) {
                // id는 이 창을 연 한 건의 것이고 누적은 Pod 전역이다 — "이 세션이 N건"으로 읽히지 않게 표기한다
                log.warn("경합 재시도 소진 — 메시지 드롭(세션 유지) 표본 sessionId={} 표본 roomId={} 누적드롭={}",
                        ls.sessionId(), ls.session().roomId(), dropped);
            }
        }
    }

    /** 단건 전송용 — 이 emit 하나가 예산을 독차지한다. */
    private void emit(LocalSession ls, OutboundFrame frame) {
        emit(ls, frame, newBudget());
    }

    /**
     * 한 연산(fan-out 1회 또는 단건 전송)이 나눠 쓰는 예산.
     *
     * <p>emit과 종료를 따로 잡는 이유: 종료 신호는 버퍼 잔여분을 최대한 흘려보내야 강퇴 직전에 보낸
     * SYSTEM(KICKED)이 전달되므로 더 넉넉히 준다. 둘 다 <b>연산 시작 시각 기준</b>이라 세션 수만큼
     * 곱해지지 않는다 — 방 fan-out은 방 전체 중계를 담당하는 Redis 구독 스레드 위에서 돈다.
     */
    private record FanOutBudget(long emitDeadline, long completeDeadline) {
    }

    /** 수명 상한 로그 간격 — 드롭 로그와 같은 규율이다(간격당 1건, 첫 발생은 반드시). */
    private boolean shouldLogLifetimeExpiry() {
        long now = System.nanoTime();
        long last = lastLifetimeExpiryLogNanos.get();
        return now - last >= DROP_LOG_INTERVAL_NANOS
                && lastLifetimeExpiryLogNanos.compareAndSet(last, now);
    }

    /**
     * {@link #local} 과 방 색인(과 그 수명을 따르는 캐시 둘)을 되돌린다. <b>자리는 건드리지 않는다.</b>
     *
     * <p>퇴장과 <b>등록 실패 보상</b>이 같은 걷기를 쓰도록 한 곳에 둔다. 둘이 따로 있으면 한쪽만
     * 빠뜨리는 변경이 조용히 들어온다 — 실제로 그랬다: 보상이 자리만 되돌리던 동안 {@code local}
     * 항목이 남았다 — 호출부의 {@code doFinally} 는 돌지만 그것이 부르는 {@code unregister} 가
     * {@code local.get} 으로 키를 만들어 자리를 못 걷는다(테스트가 7건 누출로 잡았다).
     *
     * <p>⚠️ <b>자리를 여기 두지 않는 이유가 있다.</b> 한때 이 메서드가 자리까지 되돌렸고 그 키를
     * {@code local.get(sessionId)} 으로 만들었다. 그러면 자리 반납이 "{@code local} 에 항목이 있을
     * 때" 로 좁아진다. 실측: 그 상태에서 {@code local.put} 직전에 던지게 하면 자리 다섯이 새고 그
     * 뒤 거부가 {@code TOO_MANY_SESSIONS} 로 바뀐다. 자리는 호출부가 <b>인자에서 키를 만들어</b>
     * 무조건 되돌린다 — 던진 지점이 어디든 같은 키가 나오므로 그 조건에 기대지 않는다.
     *
     * <p>⚠️ <b>이 구분에 테스트는 없다.</b> 그 둘이 갈리는 유일한 지점이 "자리 추가 ~
     * {@code local.put} 사이의 던짐" 인데, 세션 레코드를 자리 확보 앞으로 옮긴 뒤로 그 사이에
     * <b>명시적</b> 할당이 없어졌다. 한때 여기 "잡을 회귀가 없다" 고 적었는데 <b>한 칸 과했다</b> —
     * {@code local.put} <b>자신이</b> 그 창 안이고 그것이 할당한다(JDK 24 {@code src.zip} 확인:
     * {@code ConcurrentHashMap.putVal} 이 {@code new Node<>(..)} 를 두 자리에서 만들고
     * {@code addCount} 가 테이블 전이로 {@code Node[]} 를 새로 잡을 수 있다). 그 할당이 OOM 으로
     * 떨어지면 <b>자리는 들어갔고 {@code local} 에는 항목이 없다</b> — 좁은 키가 정확히 못 걷는 그
     * 상태다. 그래서 인자 키는 보험이 아니라 <b>여전히 하중</b>이다.
     *
     * <p>그 OOM 갈래에 테스트를 붙이지 않는 이유는 따로다 — 시임을 만들려면 <b>불변식이 금지한
     * 코드를 그 구간에 넣어야</b> 한다. 보증은 테스트가 아니라 "던진 지점이 어디든 같은 키가
     * 나온다" 는 구조이고, 실측은 1회(좁은 키 + {@code local.put} 직전 던짐 → 자리 다섯 누출)다.
     *
     * @param roomId 방 색인에서 걷을 방 — 세션이 {@code local} 에 있으면 <b>세션 자신의</b> 방이어야
     *               한다(등재를 그 값으로 했으므로).
     */
    private void rollbackLocalIndexes(String sessionId, UUID roomId) {
        local.remove(sessionId);
        // 방이 비면 항목까지 걷어낸다 — 남기면 방송이 끝난 방이 계속 쌓인다(인덱스 자체가 누수원이 됨).
        roomSessions.computeIfPresent(roomId, (room, sessionIds) -> {
            sessionIds.remove(sessionId);
            if (sessionIds.isEmpty()) {
                activeCountCache.remove(roomId);   // 인덱스와 같은 수명 — 남기면 이쪽이 누수원이 된다
                lastPushedCount.remove(roomId);    // 같은 이유 — 방이 사라지면 마지막 값도 쓸모가 없다
                return null;
            }
            return sessionIds;
        });
    }

    /**
     * (유저, 방) 자리 하나를 되돌린다. 빈 항목은 걷는다 — 남기면 그것이 누수원이다.
     *
     * <p>호출부가 둘이다 — 퇴장({@code unregister})과 등록 실패 보상. 둘이 각자 걷으면 한쪽만
     * "빈 항목 걷기" 를 빠뜨리는 변경이 조용히 들어온다. 다만 <b>키를 만드는 방식은 다르다</b>:
     * 퇴장은 세션 레코드에서 읽고, 보상은 인자에서 만든다({@link #rollbackLocalIndexes} 참고).
     */
    private void releaseSlot(UUID userId, UUID roomId, String sessionId) {
        userRoomSessions.computeIfPresent(new UserInRoom(userId, roomId), (userInRoom, sessionIds) -> {
            sessionIds.remove(sessionId);
            return sessionIds.isEmpty() ? null : sessionIds;
        });
    }

    /**
     * 자리가 꽉 찼을 때만 그 (유저, 방)의 <b>응답 없는</b> 세션을 거둔다. 거둔 수를 돌려준다.
     *
     * <p><b>왜 필요한가 — 그리고 무엇을 닫지 못하는가.</b> 소켓이 조용히 끊겨도 그 세션은 생존
     * 확인이 돌 때까지 자리를 들고 있다. 닫는 것은 그중 <b>마지막 구간 하나</b>다: 생존 루프가
     * 종료를 발화한 뒤 {@code cleanup} 이 자리를 걷기까지의 ≤8초.
     *
     * <p>⚠️ <b>첫 점검까지의 약 2분은 닫지 못한다 — 원리적으로 닫을 수 없다.</b> 한때 이 문단이
     * 그 2분을 reap 의 근거로 적었는데, 기한에 {@code pingInterval} 을 더해 넘기도록 고친 뒤로는
     * 거짓이다(그 수정이 없으면 살아 있는 세션을 끊는다 — 아래 참고). 더 근본적으로, 생존 시계가
     * <b>등록 시각으로 초기화</b>되므로 등록 직후 죽은 소켓은 첫 점검 전까지 "살아 있지만 조용한"
     * 시청자와 <b>구분 자체가 불가능</b>하다. 모바일 핸드오버로 조용히 끊기고 재접속하는 그
     * 시나리오가 정확히 그 구별 불가능한 경우다. 그 창을 닫으려면 "살아 있는 자리를 끊지 않는다" 를
     * 포기해야 하고, 그래서 포기하지 않는 쪽을 택했다.
     *
     * <p>상한의 "왜 5인가" 근거가 탭·새로고침 경합만 셌던 것은 사실이고, 그 창이 남아 있다는 것도
     * 사실이다. 즉 이 메서드는 그 문제를 <b>줄이지만 없애지 않는다</b> — 다음 사람이 그 창을 근거로
     * 기한을 되돌리지 않도록 적어 둔다.
     *
     * <p><b>왜 이 방식인가.</b> 대안은 "가장 오래된 세션을 밀어내기"였는데 그건 공격자에게
     * <b>남의 살아 있는 세션을 끊는 능력</b>을 준다(토큰 한 장으로 상한만큼 붙으면 정당한 사용자가
     * 쫓겨난다). 여기서 밀어내는 것은 이미 생존 기한을 넘긴 세션뿐이므로 그 능력이 생기지 않는다 —
     * 자리를 비우는 유일한 근거가 "그 소켓은 이미 죽었다" 다.
     *
     * <p>꽉 차지 않았으면 맵 조회 하나로 끝난다 — 접속마다 도는 자리라 그 경로에 비용을 두지 않는다.
     * 그 바깥 검사는 <b>권고일 뿐</b>이다: {@code computeIfPresent} 안에 full 재확인이 없으므로,
     * 사이에 다른 스레드의 퇴장이 자리를 비우면 꽉 차지 않은 집합에서도 죽은 자리를 거둔다. 거두는
     * 대상이 어차피 생존 확인이 가져갈 세션이고 자리 걷기가 멱등이라 피해가 없어 그대로 둔다 —
     * 안쪽 재확인을 넣으면 "꽉 찼을 때만" 이 두 곳에 생겨 둘이 어긋날 수 있다.
     *
     * <p>⚠️ <b>자리만 비운다 — 종료는 하지 않는다.</b> 한때 여기서 {@code terminateUnresponsive} 를
     * 불렀는데, 그러면 <b>Lettuce NIO 이벤트루프에서 최대 250ms spin</b> 하는 경로가 된다. 이 메서드는
     * {@code runSession} 안에서 돌고 그 upstream 마지막 신호가 입장 게이트의 Redis 응답이며
     * (streaming-app main 에 스케줄러 전환이 <b>0건</b>이다) 종료 하나가
     * {@link #DEFAULT_COMPLETE_SPIN_NANOS}(50ms)짜리 예산을 <b>세션마다 새로</b> 받으므로 5건이면
     * 250ms 다. 이 파일이 두 자리에서 금지한 모양이다 — 1013 종료가 공유 예산을 쓰는 이유도,
     * "기다리는 쪽은 Netty 이벤트루프에서 spin할 수 있다 — 그 루프에 붙은 다른 커넥션까지 함께
     * 멈춘다" 도 같은 사실이다. 하필 이 메서드의 발동 조건(자리가 꽉 차고 전부 죽음)이 재접속 폭주
     * 구간이라, 그 구간에 이 파드의 모든 Redis 응답이 함께 멈춘다.
     *
     * <p>종료는 각 세션의 생존 확인이 이미 맡고 있다 — 그쪽은 {@code Schedulers.parallel()} 에서
     * 세션 하나씩 돈다. 자리를 선반납해도 그 수거가 늦어지지 않는다(다음 틱이 간격 하나 안에 온다).
     * 자리 회계와 종료를 가른 것이 이 설계의 값이다.
     *
     * <p>그래서 거둔 세션이 {@link #local} 에는 잠깐 남는다 — {@code (유저, 방)}당 실제 세션 수가
     * <b>최대 2배(10), 지속 ≤8초</b>가 된다. 그 수는 종료 상한 둘에서 나온다:
     * {@code FORCED_CLOSE_GRACE 3s + CLOSE_FLUSH_TIMEOUT 5s}. 그리고 reap 은 키당
     * <b>{@code livenessDeadline + pingInterval}</b>(기본 120초)에 한 번보다 자주 발동할 수 없다 —
     * 새로 들어온 다섯이 그 기한 전에는 unresponsive 가 되지 않는다. <b>8s ≪ 120s</b> 라 정상 상태
     * 상한이 10 에서 멈추고, 그 위로 쌓이지 않는다.
     * ⚠️ 이 결론은 그 비에 매달려 있고 <b>묶는 양은 기한 하나가 아니라 기한 + 간격이다</b>(한때
     * "90초" 와 "liveness-deadline 을 8초 밑으로" 로 적어 손잡이를 잘못 가리켰다). 그 합을 8초
     * 밑으로 내리거나 종료 상한을 키우면 선형으로 증가한다(실측: 정리 없이 사이클 4회 →
     * local 5·10·15·20).
     * ⚠️ 그리고 이 ≤8초에는 <b>틱 지연 항이 없다</b> — 뺀 250ms spin 이 사라진 것이 아니라
     * {@code Schedulers.parallel} 로 옮겨 갔고, 그 풀이 밀리면 그만큼 이 창에 더해진다. 그 교환을
     * 택한 이유는 재접속 폭주에 이 파드의 Redis 응답 전체를 세우는 쪽이 더 나쁘기 때문이다.
     * ⚠️ 그리고 그 하한은 <b>강제되지 않는다</b> — {@code SessionTimeouts} 의 가드 셋(양수 ·
     * 기한 ≥ 2×간격 · 최소 수명 > 첫 점검)을 {@code (기한 2s, 간격 1s)} 가 전부 통과한다(실측).
     * 가드를 더하지 않는 이유는 {@code ChatSessionLivenessTest} 가 기한 {@code 3s} 를 <b>의도적으로</b>
     * 써서 기다리지 않기 때문이다 — 그 구성에서는 세션이 한 개라 위 증가가 문제가 되지 않는다.
     * 즉 위 수는 <b>운영 기본값에서의</b> 것이다.
     *
     * <p>그 창이 파생시키는 것 둘, 알고 둔다. 첫째, 거둔 세션이 아직 {@link #local} 에 있으므로
     * {@link #reregister} 의 {@code containsKey} 가드를 통과해 하트비트를 한두 주기 더 받는다.
     * 둘째, 그래서 같은 창의 Redis 명부가 최대 2배의 항목을 든다 — 다만 <b>보고되는 시청자 수는
     * 바뀌지 않는다</b>(그 수가 {@code HVALS distinct} 라 같은 유저의 중복은 세지 않고, 이 창의
     * 중복은 정의상 같은 (유저, 방)이다). 한때 이 줄이 "시청자 수가 그만큼 부푼다" 로 적혀 바로
     * 뒤 문장과 모순이었다. 둘 다 유계이고
     * (≤8초) 자가치유라 손대지 않는다 — 이 파일이 하트비트 과다계수를 두고 세운 판단과 같다
     * ("완전히 닫으려면 세션별 직렬화가 필요하고, 유계·자가치유인 과다계수에 그 값은 과하다").
     * 시청자 수는 {@code HVALS distinct} 라 <b>같은 유저의</b> 중복은 수를 바꾸지 않는다는 점도 있다.
     */
    public int reapUnresponsiveIfFull(UUID userId, UUID roomId, Duration livenessDeadline) {
        UserInRoom key = new UserInRoom(userId, roomId);
        Set<String> occupied = userRoomSessions.get(key);
        if (occupied == null || occupied.size() < maxSessionsPerUserPerRoom) {
            return 0;
        }
        List<String> dead = new ArrayList<>();
        userRoomSessions.computeIfPresent(key, (userInRoom, sessionIds) -> {
            for (String sessionId : sessionIds) {
                if (isUnresponsive(sessionId, livenessDeadline)) {
                    dead.add(sessionId);
                }
            }
            sessionIds.removeAll(dead);
            return sessionIds.isEmpty() ? null : sessionIds;
        });
        if (!dead.isEmpty()) {
            // 센다 — 거부와 수거를 가르지 못하면 "공격" 과 "모바일 핸드오버" 를 가를 수 없고,
            // 그 둘을 가르는 것이 이 상한을 운영하는 사람의 일이다. sessionCut(LIVENESS) 로는
            // 안 된다(태그가 사유 하나라 생존 루프가 거둔 것과 구분되지 않는다).
            slotsReaped.addAndGet(dead.size());
        }
        return dead.size();
    }

    /**
     * 죽은 자리를 거둔 누적 수.
     *
     * <p>{@code public} 인 이유는 지표 배선이 쓰기 때문이다
     * ({@code ChatSessionGaugeConfig} 의 {@code chat.sessions.reaped}). 한때 이 문장이 참이 아니었다 —
     * 접근자만 만들고 미터를 등록하지 않은 채 {@code .md} 두 곳({@code infra/AGENTS.md} ·
     * {@code modules/chat/AGENTS.md})이 그 지표를 사실로 적었다. "세 곳" 으로 센 적이 있는데 그
     * 셋째는 <b>이 javadoc 자신</b>이었다.
     */
    public long slotsReaped() {
        return slotsReaped.get();
    }

    /** 마지막 상한 거부 로그로부터 간격이 지났으면 true. {@link #shouldLogContentionDrop()} 과 같은 꼴이다. */
    private boolean shouldLogAdmissionCap() {
        long now = System.nanoTime();
        long last = lastAdmissionCapLogNanos.get();
        return now - last >= DROP_LOG_INTERVAL_NANOS
                && lastAdmissionCapLogNanos.compareAndSet(last, now);
    }

    /**
     * 동시 세션 상한으로 거부한 누적 수.
     *
     * <p>{@code public} 인 이유는 지표 배선이 쓰기 때문이다({@code ChatSessionGaugeConfig}). 집행이
     * 보이지 않으면 "상한이 공격을 막고 있다" 와 "상한이 정상 시청자를 막고 있다" 를 가를 수 없고,
     * 후자는 우리가 만든 장애다 — 그래서 집행과 그 관측을 같은 커밋에 둔다.
     */
    public long admissionCapped() {
        return admissionCapped.get();
    }

    /** 마지막 드롭 로그로부터 간격이 지났으면 true. 경쟁 시 CAS로 한 스레드만 통과한다. */
    private boolean shouldLogContentionDrop() {
        return shouldLog(lastContentionDropLogNanos);
    }

    /** 재등록 실패 로그 간격. 경합 드롭과 <b>따로</b> 센다 — 공유하면 한 회차가 서로를 가린다. */
    private boolean shouldLogReregisterFail() {
        return shouldLog(lastReregisterFailLogNanos);
    }

    /** 시청자 수 갱신 실패 로그 간격. 재등록 실패와 같은 회차에 나므로 반드시 따로다. */
    private boolean shouldLogViewerPushFail() {
        return shouldLog(lastViewerPushFailLogNanos);
    }

    private static boolean shouldLog(AtomicLong latch) {
        long now = System.nanoTime();
        long last = latch.get();
        return now - last >= DROP_LOG_INTERVAL_NANOS && latch.compareAndSet(last, now);
    }

    /** 세션 하나를 위한 예산 — 단건 전송과 단건 종료가 쓴다. 세지 않는다({@link #newSharedBudget} 참고). */
    private FanOutBudget newBudget() {
        long now = System.nanoTime();
        return new FanOutBudget(now + contentionSpinNanos, now + completeSpinNanos);
    }

    /**
     * 한 번의 fan-out이 <b>세션들과 나눠 쓸</b> 예산. 방·계정 단위로 여럿에게 닿는 모든 경로가 이것을 쓴다.
     *
     * <p>{@link #newBudget}과 만드는 값이 같은데 따로 둔 이유는 <b>세기 때문</b>이다. 그리고 세는 이유는
     * 이것이 관측 지표가 아니라 불변식이라서다 — 호출마다 새 예산을 주는 쪽으로 되돌려도 드롭은
     * <b>줄거나 같으므로</b> 결과를 보는 단언으로는 구별되지 않는다. 그 회귀를 잡는 수단이 inbox 크기와
     * 드롭 카운터뿐일 때는 원리적으로 관측이 안 되고, 재시도 상한이 세션 수에 비례해 곱해지는 것을
     * (그것도 방 전체 중계를 담당하는 Redis 구독 스레드 위에서) 아무도 알아채지 못한다.
     *
     * <p><b>단건 경로를 여기 섞지 마라.</b> 한때 셈이 {@link #newBudget} 안에 있었는데, 그 경로는
     * 메시지마다 불리므로 모든 ACK·유니캐스트가 테스트만 읽는 카운터를 위해 경합 CAS를 하나씩 물었다.
     * 불변식은 "fan-out 하나가 예산 하나"이고 단건은 그 불변식의 대상이 아니다.
     *
     * <p>⚠️ <b>이 갈라짐이 스스로를 지키지는 못한다.</b> 기존 경로가 {@link #newBudget}으로 돌아가면
     * 그 경로를 덮는 단언이 깨지지만, <b>일곱 번째 경로를 새로 만들고 열거 테스트에 한 줄 적지 않으면
     * 아무도 묻지 않는다</b> — 조건절이 자기 자신이다. 구조로 막으려면 {@link #forEachInRoom}·
     * {@link #forEachSessionOf}가 예산을 만들어 넘기게 해서 fan-out 측에 per-session 예산을 만들 수단
     * 자체를 없애야 하고, 그러면 이 셈도 필요 없어진다. 오늘의 여섯 열거는 완전하다.
     */
    private FanOutBudget newSharedBudget() {
        budgetsCreated.increment();
        return newBudget();
    }

    /**
     * 만든 fan-out 공유 예산의 누적 수. <b>관측용이 아니라 불변식 고정용이다</b>(위 참고).
     *
     * <p>{@code LongAdder}인 것은 <b>지금 근거가 약하다.</b> 셈을 단건 경로에서 뺀 뒤로는 스레드 간
     * 쓰기 경합이 사실상 없고, {@code LongAdder}가 이기는 조건은 쓰기/읽기 비율이 아니라 그 경합이다 —
     * {@code AtomicLong}으로도 족하다(한때 이 자리에 "쓰기가 읽기보다 많아서"라고 적혀 있었는데
     * 조건을 잘못 짚은 것이다). 비용이 중립이라 두지만, 이 선택을 근거로 확장하지 말 것.
     *
     * (테스트 진입점)
     */
    long budgetsCreated() {
        return budgetsCreated.sum();
    }

    /**
     * 버퍼에 자리가 없어 밀어넣지 못한 경우 — 그 클라가 소비를 못 따라온다는 신호라 세션을 끊는다.
     * {@code FAIL_ZERO_SUBSCRIBER}도 "구독 전"이 아니라 "구독 전에 버퍼가 찼다"는 뜻이라 같이 묶는다.
     * 이미 끝난 세션({@code FAIL_TERMINATED}/{@code FAIL_CANCELLED})과 일시 경합
     * ({@code FAIL_NON_SERIALIZED})은 해당하지 않는다. (정책을 한 곳에 모아 테스트로 고정하기 위한 진입점)
     */
    boolean consumerCannotKeepUp(Sinks.EmitResult result) {
        return result == Sinks.EmitResult.FAIL_OVERFLOW
                || result == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER;
    }

    /** 경합으로 버린 메시지 누적 수. (관측용 — 0이 아니면 그만큼 화면이 어긋났다는 뜻) */
    long droppedOnContention() {
        return droppedOnContention.get();
    }


    /**
     * 데이터 채널 쪽 종료 — 버퍼에 남은 걸 흘려보낸 뒤 곱게 닫는 정상 경로다.
     * 경합으로 놓쳐도 {@code terminate}의 제어 채널이 종료를 보장하므로, 여기 실패는 치명적이지 않다.
     */
    private void complete(LocalSession ls, long deadline) {
        Sinks.EmitResult result = emitSerially(() -> ls.sink().tryEmitComplete(), deadline);
        if (result == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
            log.warn("데이터 채널 종료 실패(경합 소진) — 제어 채널로 종료한다 sessionId={} roomId={}",
                    ls.sessionId(), ls.session().roomId());
        }
    }

    /**
     * Sink는 동시 emit을 직렬화하지 않는다 — 경합하면 {@code FAIL_NON_SERIALIZED}를 돌려주고 값을 버린다.
     * 한 세션에 ack(WS 이벤트루프)와 방 브로드캐스트(Redis 구독 스레드)가 동시에 들어오므로, Reactor 권장대로
     * 경합 구간만 짧게 busy-loop 재시도한다.
     *
     * <p>상한을 짧게 잡는 이유: 락을 쥔 쪽은 버퍼를 비우는 동안(직렬화까지) 쥐고 있고, 기다리는 쪽은
     * <b>Netty 이벤트루프에서 spin</b>할 수 있다 — 그 루프에 붙은 다른 커넥션까지 함께 멈춘다.
     * 소진 시 처분은 호출자가 정한다.
     *
     * <p>예산은 <b>절대 시각(deadline)</b>으로 받는다. 방 fan-out은 세션 수만큼 이 메서드를 부르는데,
     * 호출마다 새 예산을 주면 상한이 세션 수에 비례해 곱해진다 — 그것도 방 전체 중계를 담당하는
     * Redis 구독 스레드 위에서. 그래서 한 번의 fan-out이 예산 하나를 나눠 쓴다.
     */
    private Sinks.EmitResult emitSerially(Supplier<Sinks.EmitResult> emitter, long deadline) {
        while (true) {
            Sinks.EmitResult result = emitter.get();
            if (result != Sinks.EmitResult.FAIL_NON_SERIALIZED || System.nanoTime() - deadline >= 0) {
                return result;
            }
            Thread.onSpinWait();
        }
    }
}
