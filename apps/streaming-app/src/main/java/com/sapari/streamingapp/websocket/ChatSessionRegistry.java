package com.sapari.streamingapp.websocket;

import java.time.Duration;
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

    @Autowired
    public ChatSessionRegistry(ChatSessionRepository sessionRepository,
                               ChatPermissionPolicy permissionPolicy,
                               ChatMetrics metrics) {
        this(sessionRepository, permissionPolicy, metrics,
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
        this.sessionRepository = sessionRepository;
        this.permissionPolicy = permissionPolicy;
        this.metrics = metrics;
        this.contentionSpinNanos = contentionSpinNanos;
        this.completeSpinNanos = completeSpinNanos;
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
                    if (shouldLogDrop()) {
                        log.warn("세션 명부 재등록 실패 — 다음 주기가 받는다(누적 {}건) 표본 sessionId={} "
                                        + "표본 roomId={} cause={}", failures, ls.sessionId(),
                                ls.session().roomId(), e.getClass().getSimpleName());
                    }
                    return Mono.empty();
                });
    }

    /** 재등록 실패 누적 — 테스트 진입점. */
    long heartbeatFailures() {
        return heartbeatFailures.get();
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
     * 큰 방에서는 그게 이 기능의 비용 전부가 된다.
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
                    if (previous == null || previous.equals(count)) {
                        // previous == null 은 이 Pod 가 그 방을 처음 보는 회차다. 보내지 않는다 —
                        // 입장 시 핸들러가 이미 ROOM_INFO 를 보냈고 그것이 기준선이다. 여기서 또
                        // 보내면 방마다 한 번씩 전원에게 같은 숫자가 중복으로 간다.
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
                    if (shouldLogDrop()) {
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

    /** 마지막 드롭 로그 시각. 첫 발생이 반드시 남도록 간격만큼 과거로 초기화한다. */
    private final AtomicLong lastDropLogNanos = new AtomicLong(System.nanoTime() - DROP_LOG_INTERVAL_NANOS);

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
            AtomicLong lastInboundNanos) {
    }

    /**
     * transport 전용: 레이트리밋에 걸렸다는 사실과 해제 시각을 이 세션에 적어둔다.
     *
     * <p>거부 자체는 레이트리밋이 하지만, <b>거부하는 비용</b>은 제한하지 못한다 — 거부 한 번에 Redis 왕복이
     * 세 번(강퇴 조회·SET NX·잔여 TTL 조회) 든다. 회선 속도로 밀면 그 비용이 그대로 반복된다.
     * 해제 시각을 알고 있는 동안은 같은 답을 다시 물을 이유가 없으므로 여기에 적어두고 로컬에서 끊는다.
     */
    public void recordRateLimited(String sessionId, long retryAfterSeconds) {
        LocalSession ls = local.get(sessionId);
        if (ls == null) {
            return;
        }
        // 상한을 둔다 — 값 출처가 바뀌어 큰 수가 들어오면 Duration 환산이 ArithmeticException을 던져
        // 인바운드 스트림을 죽인다. 레이트리밋 창은 분 단위를 넘길 이유가 없다.
        long clamped = Math.max(0, Math.min(retryAfterSeconds, MAX_RATE_LIMIT_WINDOW_SECONDS));
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
        // 세션마다 unicast Sink 1개(연결당 아웃바운드 1개). onBackpressureBuffer: 구독 전 emit·일시 적체 보관.
        // 버퍼는 유계 — 무제한이면 소비하지 않는 클라 하나가 Pod 힙을 잠식한다(초과 처리는 emit 참고).
        local.put(sessionId, new LocalSession(sessionId, session,
                Sinks.many().unicast().onBackpressureBuffer(Queues.<OutboundFrame>get(OUTBOUND_BUFFER_SIZE).get()),
                Sinks.one(), new AtomicReference<>(),    // closeStatus는 종료가 정해질 때 채워진다(null=미정)
                // 둘 다 nanoTime 기준이라 0을 센티널로 쓰면 안 된다 — nanoTime 원점은 임의고 음수일 수 있어,
                // 0과 비교하면 등록 직후부터 "창 안"으로 오판해 전송이 통째로 막힌다.
                new AtomicLong(System.nanoTime() - REJECTION_REPLY_INTERVAL_NANOS),
                new AtomicLong(System.nanoTime()),
                // 입장 게이트가 방금 확인했으므로 창을 지금부터 연다 — 첫 프레임에서 또 묻지 않는다.
                new AtomicLong(System.nanoTime()),
                new AtomicBoolean(),
                // 생존 시계도 지금부터 — 방금 핸드셰이크를 마쳤으므로 응답이 없는 상태가 아니다.
                new AtomicLong(System.nanoTime())));
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
        // 자체가 Pub/Sub이라 아무도 못 받고, 그때 남는 건 "저장은 되고 발신자만 ack 받는" 상태다 —
        // 강퇴·밴·종료 마커가 같이 열려 있어 그 창의 전송이 이력에 그대로 쌓인다(레이트리밋은
        // 아니다 — 그쪽은 Pod 로컬 창으로 강등해 상한이 남는다). 그 구간을 막을지는
        // 전송 경로(publish 실패 처리)의 정책 문제라 여기서 결정하지 않는다.
        return sessionRepository.add(session.roomId(), sessionId, session.userId())
                .onErrorResume(e -> {
                    log.warn("세션 명부 등재 실패 — 접속은 진행(시청자 수만 부정확) roomId={} cause={}",
                            session.roomId(), e.getClass().getSimpleName());
                    return Mono.empty();
                });
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
     * {@code RedisRateLimiter} 의 200ms 상한에 걸려 <b>그 Pod 의 레이트리밋 집행이 로컬 창으로
     * 강등</b>된다. 실측으로 재 봤다 — 12,000 엔트리 해시에 동시 100건을 걸면 그 순간의 SETNX 가
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
    public Mono<Void> closeUserEverywhere(UUID userId) {
        // 밴도 정책상 종료라 1008 — 강퇴와 같은 코드다. 프론트가 "재접속하지 말 것"으로 읽는다.
        return Mono.fromRunnable(() -> {
            FanOutBudget budget = newSharedBudget();
            forEachSessionOf(userId, ls -> {
                metrics.sessionCut(SessionCutReason.MODERATION);
                terminate(ls, CloseStatus.POLICY_VIOLATION, budget.completeDeadline());
            });
        });
    }

    /**
     * 이 Pod에서 그 사람의 세션을 전부 훑는다.
     *
     * <p><b>방 색인을 못 쓴다</b> — 계정 조치는 방을 모른 채로 오고, {@code userId → 세션} 색인은 두지
     * 않았다. 두면 {@code register}/{@code unregister}마다 동기화 대상이 하나 늘고 그 어긋남이 조용한
     * 버그가 되는데, 그 비용을 낼 만큼 이 경로가 잦지 않다. 12,000 세션 전체 스캔이 58µs다(실측, 워밍업 후 최소값).
     * 방 fan-out이 색인을 갖는 것은 <b>메시지마다</b> 돌기 때문이고, 이쪽은 시간당 몇 건이다.
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
     * <p>⚠️ <b>계정 상태는 그렇지 않다.</b> 한때 여기에 "역할이 바뀌었으면 바뀐 역할로 온다"고
     * 적혀 있었는데 거짓이다 — 역할은 live DB 가 아니라 <b>access 토큰 claims</b> 에서 오고, live-app 의
     * 인증 필터가 폐기 저장소를 보지 않는다(그 파일이 스스로 "폐기 갭"으로 적어 두었다). 즉 역할 강등·
     * 탈퇴·로그아웃이 반영되기까지의 실제 상한은 <b>이 수명 + access 잔여 TTL</b> 이다. 특권 세션
     * (방 주인·ADMIN)은 그 창 동안 {@code senderEmail} 과 마스킹 전 원문을 계속 받으므로 그건 PII 창이다 —
     * 짧은 별도 상한이나 밴과 같은 전파 채널이 그쪽의 처방이고, 이 수명이 대신하지 못한다.
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
     * 이 수가 보이지 않으면 "파드 하나가 몇 세션까지 버티는가" 를 물을 수 없고, 그러면 연결 수
     * 상한 같은 값을 <b>고를 근거가 없다</b> — 그래서 상한보다 이 게이지가 먼저다.
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
            if (shouldLogDrop()) {
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

    /** 마지막 드롭 로그로부터 간격이 지났으면 true. 경쟁 시 CAS로 한 스레드만 통과한다. */
    private boolean shouldLogDrop() {
        long now = System.nanoTime();
        long last = lastDropLogNanos.get();
        return now - last >= DROP_LOG_INTERVAL_NANOS && lastDropLogNanos.compareAndSet(last, now);
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
