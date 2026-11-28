package com.sapari.streamingapp.websocket;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatSessionRepository;

import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 시청자 수 — 입장 때 내려가는 수의 캐시와 조회 합치기, Pod 당 하나인 재등록 타이머, 그 타이머에 얹힌
 * 시청자 수 갱신 push.
 *
 * <p>세션 색인({@link LocalSessionIndex})은 읽기만 하고, 방이 비어 항목이 걷힐 때는 색인이 그 원자 구간
 * 안에서 {@link #forgetRoom} 을 부른다. 갱신 push 는 레지스트리의 세션별 차등 팬아웃
 * ({@code ChatSessionRegistry.sendToRoomGated})을 그대로 탄다 — 예산·직렬화 공유 규칙이 한 곳에 남는다.
 */
@Slf4j
final class ViewerCounts {

    private final ChatSessionRepository sessionRepository;   // Redis HASH 어댑터(T6) — 크로스 Pod activeCount
    private final ChatMetrics metrics;
    private final LocalSessionIndex index;

    /** 세션별 차등 팬아웃 — {@code ChatSessionRegistry::sendToRoomGated}. */
    private final BiFunction<UUID, Function<ChatSession, OutboundMessage>, Mono<Void>> gatedFanOut;

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

    /** 시청자 수 캐시 수명. 짧게 잡아도 몰리는 순간의 중복 조회는 대부분 걷힌다. */
    private static final long ACTIVE_COUNT_CACHE_NANOS = Duration.ofSeconds(3).toNanos();

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

    /** 사유별 로그 솎아내기 — 재등록 실패 · 시청자 수 갱신 실패. 공유하지 않는 이유는 {@link LogThrottle}. */
    private final LogThrottle reregisterFailLog = new LogThrottle();
    private final LogThrottle viewerPushFailLog = new LogThrottle();

    ViewerCounts(ChatSessionRepository sessionRepository, ChatMetrics metrics, LocalSessionIndex index,
                 BiFunction<UUID, Function<ChatSession, OutboundMessage>, Mono<Void>> gatedFanOut) {
        this.sessionRepository = sessionRepository;
        this.metrics = metrics;
        this.index = index;
        this.gatedFanOut = gatedFanOut;
    }

    /** 방 항목이 걷힐 때 색인의 원자 구간 안에서 불린다 — 방 색인과 같은 수명인 둘을 함께 걷는다. */
    void forgetRoom(UUID roomId) {
        activeCountCache.remove(roomId);   // 인덱스와 같은 수명 — 남기면 이쪽이 누수원이 된다
        lastPushedCount.remove(roomId);    // 같은 이유 — 방이 사라지면 마지막 값도 쓸모가 없다
    }

    /**
     * Pod 안의 모든 로컬 세션을 주기적으로 <b>재등록</b>한다.
     *
     * <p>이게 없으면 세션 명부가 "마지막 입장 이후" 기준으로만 갱신돼, Pod 가 크래시한 뒤 남은 항목이
     * 키 TTL(24시간)까지 시청자 수를 부풀린다. 그리고 한산한 방은 신규 입장이 없어 방송 중에 키가
     * 만료되기도 했다 — 둘 다 방송 내내 틀린 수가 보이는 모양이다.
     *
     * <p><b>세션당 타이머가 아니라 Pod 당 하나다.</b> 세션당으로 두면 수천 개 타이머가 생기고, 이미
     * 세션마다 생존 확인 인터벌이 하나 있어 그걸 두 배로 만든다. 세션 색인이 로컬 세션 전부를 알고
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
     * 미완료라서). 끝난 회차의 길이는 {@code chat.sessions.heartbeat.duration} 이 재고, 안 끝나는 회차는
     * 그 Timer 에 안 잡히는 대신 버려진 tick 이 {@code chat.sessions.heartbeat.skipped} 로 오른다.
     *
     * <p>⚠️ 어댑터의 정적 가드가 지키는 하한은 <b>3배</b>다(4배가 아니다) — 지금 값이 4배지만 3배로
     * 줄여도 가드는 통과한다. 즉 네 배는 현재 값의 성질이고 가드가 보장하는 값이 아니다.
     */
    void startHeartbeat() {
        Duration interval = sessionRepository.heartbeatInterval();
        heartbeat = Flux.interval(interval, interval)
                // 버린 tick 을 센다 — 회차 시간은 끝난 회차만 재므로, 회차가 멈추면 이 수만 오른다.
                .onBackpressureDrop(tick -> metrics.heartbeatTickSkipped())
                .concatMap(tick -> Mono.defer(() -> {
                            long started = System.nanoTime();
                            return Flux.fromIterable(index.sessions())
                                    .flatMap(this::reregister)
                                    .then()
                                    // 같은 타이머에 시청자 수 갱신을 얹는다 — 둘 다 "이 Pod 가 주기마다 하는 일"
                                    // 이고 타이머를 하나 더 두면 세션 수만큼의 비용이 두 배가 된다. 순서가 이쪽인
                                    // 이유는 재등록이 먼저 끝나야 그 회차의 수가 좀비를 걷어낸 값이기 때문이다.
                                    .then(pushViewerCounts())
                                    .doOnSuccess(ignored -> metrics.heartbeatRoundCompleted(
                                            Duration.ofNanos(System.nanoTime() - started)));
                        })
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
     *
     * <p><b>방 종료로 닫힌 세션도 뺀다.</b> 그 세션은 떠나기 전까지(종료 상한만큼) 명부에 남아 위 확인을
     * 통과하는데, 떠날 때 HDEL 을 보내지 않는다({@code ChatSessionRegistry.unregister}). 그래서 {@code clearRoom}
     * 뒤에 착지한 재등록을 걷을 명령이 없고 되살아난 키가 TTL(24시간)까지 남는다. 이 확인도 위와 같은 창
     * (확인과 쓰기 사이 왕복 한 번)은 남긴다.
     */
    Mono<Void> reregister(LocalSession ls) {
        return Mono.defer(() -> {
                    if (!index.contains(ls.sessionId()) || ls.closedByRoomEnd().get()) {
                        return Mono.<Void>empty();
                    }
                    return sessionRepository.heartbeat(
                            ls.session().roomId(), ls.sessionId(), ls.session().userId());
                })
                .onErrorResume(e -> {
                    long failures = heartbeatFailures.incrementAndGet();
                    if (reregisterFailLog.tryAcquire()) {
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
     * <b>안 끝나면</b> 늘지 않는다 — 위 주기 javadoc 이 그 갈래를 적는다. 그 갈래는
     * {@code chat.sessions.heartbeat.skipped} 가 오르는 것으로 보이므로, 이 수 하나로 "재등록이 돌고
     * 있다" 를 결론 내리면 침묵이 안전으로 읽힌다.
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
        return Flux.fromIterable(index.roomsSnapshot())
                .concatMap(this::pushViewerCount)
                .then();
    }

    private Mono<Void> pushViewerCount(UUID roomId) {
        // 캐시(getActiveCount)를 거치지 않는다. 그 캐시는 입장 폭주를 막으려고 조회를 억제하는
        // 장치인데, 이 타이머가 하려는 일이 정확히 "변화를 관측" 이라서 억제를 거치면 캐시 수명 안의
        // 변화를 못 본다. 그리고 억제할 이유도 없다 — 이쪽은 방당 회차당 한 번으로 이미 유계다.
        return sessionRepository.count(roomId)
                .flatMap(count -> {
                    Long previous = lastPushedCount.put(roomId, count);
                    if (!index.hasRoom(roomId)) {
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
                    return gatedFanOut.apply(roomId,
                            session -> session.isRoomOwner() ? ownerInfo : viewerInfo);
                })
                .onErrorResume(e -> {
                    // 수를 모르면 보내지 않는다 — 다음 회차가 받는다. 여기서 터뜨리면 그 뒤로 이 Pod 의
                    // 모든 방 갱신이 멈춘다(concatMap 상류가 끝난다).
                    if (viewerPushFailLog.tryAcquire()) {
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

    Mono<Long> getActiveCount(UUID roomId) {
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
                    // 캐시 쓰기가 cache() 상류라 합류자가 50이어도 쓰기는 1회다 — 병합 전에는
                    // 호출자마다 각자 썼다.
                    .doOnNext(count -> cacheIfRoomStillLocal(room, count))
                    // ⚠️ 값까지 비교해서 지운다. remove(room) 한 인자로 지우면 "내 비행이 끝났다"가
                    // 아니라 "그 방의 비행 자리를 비운다"가 되어, 그 사이에 설치된 남의 비행을
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
     * 조회가 끝난 시점에도 그 방이 이 Pod에 남아 있을 때만 캐시에 담는다.
     *
     * <p>담는 시점과 회수 시점이 다른 락 구간이라 그냥 넣으면 샌다 — Redis를 다녀오는 수 ms 사이에
     * 마지막 세션이 끊기면 회수({@code unregister})가 먼저 지나가고 그 뒤에 항목이 새로 생긴다.
     * 그 방에 아무도 다시 안 들어오면 영영 남는다. 방 인덱스와 같은 락 안에서 판단해 그 창을 없앤다.
     */
    private void cacheIfRoomStillLocal(UUID roomId, long count) {
        index.ifRoomPresent(roomId,
                () -> activeCountCache.put(roomId, new CachedCount(count, System.nanoTime() + ACTIVE_COUNT_CACHE_NANOS)));
    }
}
