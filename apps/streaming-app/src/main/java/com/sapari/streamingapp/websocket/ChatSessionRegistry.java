package com.sapari.streamingapp.websocket;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;

import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatSessionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

/**
 * {@link ChatSessionManager} 구현 — 이 Pod에 붙은 WS 세션의 로컬 명부 + Redis HASH(크로스 Pod 집계) 조율.
 *
 * <p><b>왜 transport(streaming-app)에 두나</b>: 실제 송신 채널을 쥐는 유일한 곳이라서. chat-core 포트는
 * reactor Sink·WS 채널을 모르고(레이어 누수 방지) 도메인 {@link ChatSession}만 다룬다. 그래서 채널(Sink)은
 * 이 구현체가 소유하고, 핸들러는 {@link #outbound(String)}로 아웃바운드 스트림을 받아 session.send()에 연결한다.
 *
 * <p><b>Sink</b>: 외부(이 레지스트리)에서 임의 시점에 값을 밀어넣는 통로. T10 구독자가 메시지를 받으면
 * {@code sendToSession}으로 해당 세션 Sink에 emit → 그 WS로 흘러나간다. unicast+버퍼라 구독(=session.send) 전
 * emit도 버퍼에 보관된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
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
     */
    private static final long CONTENTION_SPIN_NANOS = Duration.ofMillis(5).toNanos();

    /**
     * 종료 신호 재시도 상한. emit보다 넉넉히 주는 이유는 종료 보장이 아니라 <b>전달 확률</b>이다 —
     * 이 신호가 걸려야 버퍼 잔여분이 흘러나가고, 강퇴 직전에 보낸 SYSTEM(KICKED)이 클라에 닿는다.
     * 놓쳐도 제어 채널이 종료 자체는 책임진다({@code terminate} 참고).
     */
    private static final long COMPLETE_SPIN_NANOS = Duration.ofMillis(50).toNanos();

    /**
     * 드롭 로그 솎아내기 간격 — 드롭이 몰리는 상황이 곧 CPU 포화라 건당 로그가 상황을 악화시킨다.
     * <b>건수가 아니라 경과시간</b>으로 센다: 건수 기준은 카운터가 프로세스 생애 누적이라 두 번째 이후의
     * 짧은 버스트가 통째로 침묵한다 — 드롭은 실제 메시지 유실이라 "조용한 유실이 되지 않게"라는
     * 이 클래스의 목표와 정면으로 어긋난다.
     */
    private static final long DROP_LOG_INTERVAL_NANOS = Duration.ofSeconds(10).toNanos();

    private final ChatSessionRepository sessionRepository;   // Redis HASH 어댑터(T6) — 크로스 Pod activeCount

    /** sessionId → (도메인 세션 + 아웃바운드 Sink). 로컬 메모리(이 Pod 한정). */
    private final Map<String, LocalSession> local = new ConcurrentHashMap<>();

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
    private record LocalSession(String sessionId, ChatSession session, Sinks.Many<OutboundMessage> sink,
            Sinks.One<CloseStatus> terminate, AtomicReference<CloseStatus> closeStatus,
            AtomicLong lastRejectionReplyNanos, AtomicLong rateLimitedUntilNanos,
            AtomicLong lastRoomAliveCheckNanos, AtomicBoolean roomEnded) {
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
        // 세션마다 unicast Sink 1개(연결당 아웃바운드 1개). onBackpressureBuffer: 구독 전 emit·일시 적체 보관.
        // 버퍼는 유계 — 무제한이면 소비하지 않는 클라 하나가 Pod 힙을 잠식한다(초과 처리는 emit 참고).
        local.put(sessionId, new LocalSession(sessionId, session,
                Sinks.many().unicast().onBackpressureBuffer(Queues.<OutboundMessage>get(OUTBOUND_BUFFER_SIZE).get()),
                Sinks.one(), new AtomicReference<>(),    // closeStatus는 종료가 정해질 때 채워진다(null=미정)
                // 둘 다 nanoTime 기준이라 0을 센티널로 쓰면 안 된다 — nanoTime 원점은 임의고 음수일 수 있어,
                // 0과 비교하면 등록 직후부터 "창 안"으로 오판해 전송이 통째로 막힌다.
                new AtomicLong(System.nanoTime() - REJECTION_REPLY_INTERVAL_NANOS),
                new AtomicLong(System.nanoTime()),
                // 입장 게이트가 방금 확인했으므로 창을 지금부터 연다 — 첫 프레임에서 또 묻지 않는다.
                new AtomicLong(System.nanoTime()),
                new AtomicBoolean()));
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
        // 강퇴·레이트리밋도 같이 열려 있어 그 창의 전송이 이력에 그대로 쌓인다. 그 구간을 막을지는
        // 전송 경로(publish 실패 처리)의 정책 문제라 여기서 결정하지 않는다.
        return sessionRepository.add(session.roomId(), sessionId, session.userId())
                .onErrorResume(e -> {
                    log.warn("세션 명부 등재 실패 — 접속은 진행(시청자 수만 부정확) roomId={} cause={}",
                            session.roomId(), e.getClass().getSimpleName());
                    return Mono.empty();
                });
    }

    /** transport 전용: 핸들러가 session.send()에 연결할 아웃바운드 스트림. (포트 아님 — chat-core는 안 씀) */
    public Flux<OutboundMessage> outbound(String sessionId) {
        LocalSession ls = local.get(sessionId);
        return ls == null ? Flux.empty() : ls.sink().asFlux();
    }

    @Override
    public Mono<Void> unregister(UUID roomId, String sessionId) {
        local.remove(sessionId);
        // 방이 비면 항목까지 걷어낸다 — 남기면 방송이 끝난 방이 계속 쌓인다(인덱스 자체가 누수원이 됨).
        roomSessions.computeIfPresent(roomId, (room, sessionIds) -> {
            sessionIds.remove(sessionId);
            if (sessionIds.isEmpty()) {
                activeCountCache.remove(roomId);   // 인덱스와 같은 수명 — 남기면 이쪽이 누수원이 된다
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
        // 조회 실패는 캐시에 담지 않는다 — 실패를 굳혀두면 그 방의 다음 입장까지 값 없이 나간다.
        return sessionRepository.count(roomId)   // HVALS distinct = 고유 유저 수
                .doOnNext(count -> activeCountCache.put(roomId,
                        new CachedCount(count, System.nanoTime() + ACTIVE_COUNT_CACHE_NANOS)));
    }

    @Override
    public Mono<Void> sendToSession(String sessionId, OutboundMessage message) {
        return Mono.fromRunnable(() -> emit(local.get(sessionId), message));
    }

    @Override
    public Mono<Void> sendToRoomLocal(UUID roomId, OutboundMessage message) {
        return Mono.fromRunnable(() -> {
            FanOutBudget budget = newBudget();
            forEachInRoom(roomId, ls -> emit(ls, message, budget));
        });
    }

    @Override
    public Mono<Void> sendToRoomGated(UUID roomId, Function<ChatSession, OutboundMessage> resolver) {
        // 세션별 차등 fan-out — resolver가 세션마다 메시지 생성(방주인 PII 게이팅·kick 분기). null이면 그 세션 skip.
        return Mono.fromRunnable(() -> {
            FanOutBudget budget = newBudget();
            forEachInRoom(roomId, ls -> {
                OutboundMessage message = resolver.apply(ls.session());
                if (message != null) {
                    emit(ls, message, budget);
                }
            });
        });
    }

    @Override
    public Mono<Void> closeUser(UUID roomId, UUID userId) {
        // 강퇴는 정책상 종료라 1008 — 프론트가 "재접속하지 말 것"으로 읽는 코드다.
        return Mono.fromRunnable(() -> {
            FanOutBudget budget = newBudget();
            forEachInRoom(roomId, ls -> {
                if (ls.session().userId().equals(userId)) {
                    terminate(ls, CloseStatus.POLICY_VIOLATION, budget.completeDeadline());
                }
            });
        });
    }

    @Override
    public Mono<Void> closeAll(UUID roomId) {
        // 방 종료는 정상 종료(1000). 사유는 앞서 보낸 SYSTEM(ROOM_ENDED)이 전달한다.
        return Mono.fromRunnable(() -> {
            FanOutBudget budget = newBudget();
            forEachInRoom(roomId, ls -> terminate(ls, CloseStatus.NORMAL, budget.completeDeadline()));
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
     * 여기서 얻으려는 건 실시간성이 아니라 <b>구간의 상한</b>이다. 창 길이가 곧 그 상한이 된다.
     *
     * @return 지금 확인해야 하면 true. false면 호출자는 묻지 않고 살아있는 것으로 본다.
     *         이미 종료가 확인된 세션은 {@link #isRoomKnownEnded}가 앞에서 걸러야 한다.
     */
    /** 마지막 확인 시각을 과거로 밀어 창이 열린 상태를 만든다. (테스트 진입점 — 30초를 기다리지 않기 위해) */
    void expireRoomAliveWindow(String sessionId) {
        LocalSession ls = local.get(sessionId);
        if (ls != null) {
            ls.lastRoomAliveCheckNanos().addAndGet(-ROOM_ALIVE_RECHECK_INTERVAL_NANOS);
        }
    }

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
        terminate(ls, CloseStatus.POLICY_VIOLATION, newBudget().completeDeadline());
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
    private void emit(LocalSession ls, OutboundMessage message, FanOutBudget budget) {
        if (ls == null) {
            return;
        }
        Sinks.EmitResult result = emitSerially(() -> ls.sink().tryEmitNext(message), budget.emitDeadline());
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
            terminate(ls, CloseStatus.SERVICE_OVERLOAD, budget.emitDeadline());
        } else if (result == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
            // 세션은 살려둔다. 대신 드롭을 세어 "조용한 유실"이 되지 않게 한다.
            // 로그는 솎아낸다 — 드롭이 나는 상황이 곧 CPU 포화라, 건당 동기 로그가 그걸 더 악화시킨다.
            long dropped = droppedOnContention.incrementAndGet();
            if (shouldLogDrop()) {
                // id는 이 창을 연 한 건의 것이고 누적은 Pod 전역이다 — "이 세션이 N건"으로 읽히지 않게 표기한다
                log.warn("경합 재시도 소진 — 메시지 드롭(세션 유지) 표본 sessionId={} 표본 roomId={} 누적드롭={}",
                        ls.sessionId(), ls.session().roomId(), dropped);
            }
        }
    }

    /** 단건 전송용 — 이 emit 하나가 예산을 독차지한다. */
    private void emit(LocalSession ls, OutboundMessage message) {
        emit(ls, message, newBudget());
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

    /** 마지막 드롭 로그로부터 간격이 지났으면 true. 경쟁 시 CAS로 한 스레드만 통과한다. */
    private boolean shouldLogDrop() {
        long now = System.nanoTime();
        long last = lastDropLogNanos.get();
        return now - last >= DROP_LOG_INTERVAL_NANOS && lastDropLogNanos.compareAndSet(last, now);
    }

    private FanOutBudget newBudget() {
        long now = System.nanoTime();
        return new FanOutBudget(now + CONTENTION_SPIN_NANOS, now + COMPLETE_SPIN_NANOS);
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
