package com.sapari.streamingapp.websocket;

import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.port.SessionCutReason;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatSessionRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;

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

    /** 고른 세션에 넣고 끊는 쪽 — 스핀 예산과 드롭·버퍼 초과 처분이 거기 있다. */
    private final SessionOutbound outbound;

    /** 이 Pod 의 세션 명부와 방 색인. */
    private final LocalSessionIndex index = new LocalSessionIndex();

    /** 시청자 수 캐시·조회 합치기·재등록 타이머·갱신 push. */
    private final ViewerCounts viewerCounts;

    /** (유저, 방) 동시 세션 자리 — 상한 집행·반납·죽은 자리 거두기. */
    private final SessionSlots slots;


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
                SessionOutbound.DEFAULT_CONTENTION_SPIN_NANOS, SessionOutbound.DEFAULT_COMPLETE_SPIN_NANOS);
    }

    /** 기본 상한으로 위임하는 테스트 진입점 — 상한이 축이 아닌 칸들이 쓴다. */
    ChatSessionRegistry(ChatSessionRepository sessionRepository,
                        ChatPermissionPolicy permissionPolicy,
                        ChatMetrics metrics) {
        this(sessionRepository, permissionPolicy, metrics, DEFAULT_MAX_SESSIONS_PER_USER_PER_ROOM,
                SessionOutbound.DEFAULT_CONTENTION_SPIN_NANOS, SessionOutbound.DEFAULT_COMPLETE_SPIN_NANOS);
    }

    /** 운영 생성자가 실제로 운영 예산을 넘기는지 재기 위한 진입점 — 넓힌 예산이 테스트에만 머물게 한다. */
    long contentionSpinNanos() {
        return outbound.contentionSpinNanos();
    }

    /** 위와 같다. 둘을 따로 읽는 이유는 뒤바꿔 넘기는 실수가 한 값만 보면 드러나지 않기 때문이다. */
    long completeSpinNanos() {
        return outbound.completeSpinNanos();
    }

    /** 예산을 넓혀 받는다 (테스트 진입점 — 넓히는 이유는 {@link SessionOutbound} 의 {@code contentionSpinNanos} 필드 javadoc). */
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
        this.slots = new SessionSlots(maxSessionsPerUserPerRoom, this::isUnresponsive);
        this.outbound = new SessionOutbound(metrics, contentionSpinNanos, completeSpinNanos);
        // this::sendToRoomGated 는 타이머가 돌 때(@PostConstruct 이후)에야 불린다.
        this.viewerCounts = new ViewerCounts(sessionRepository, metrics, index, this::sendToRoomGated);
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
        return slots.max();
    }





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
     * <p>⚠️ <b>파드 로컬이다 — 전역 상한이 아니다.</b> 자리 맵({@link SessionSlots})은 이 싱글톤이
     * 들고 있는 인스턴스라 실효 상한은 <b>상한 × 레플리카 수</b>다. 공격자가 LB 라우팅을 통제할 필요도 없다 —
     * 핸드셰이크가 흩어지면 각 파드의 {@code compute} 가 서로 다른 맵을 봐서 전부 통과한다. 이 파일은
     * 다중 파드를 설계 전제로 못 박고 있고({@link ViewerCounts} 의 시청자 수 조회 합치기 실측이 "파드 6개" 기준이다), 크로스 파드
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




    /** 수명 상한으로 끊은 누적 수 — 로그를 솎아도 규모는 남긴다. */
    private final AtomicLong lifetimeExpiredCount = new AtomicLong();

    /** 수명 상한 로그 솎아내기. */
    private final LogThrottle lifetimeExpiryLog = new LogThrottle();


    /**
     * 방 종료를 다시 확인하는 간격. 종료 신호를 놓친 Pod가 끝난 방에 글을 받아주는 구간의 상한이 된다.
     * 짧게 잡을수록 그 구간은 줄지만 정상 전송에 붙는 Redis 왕복 빈도가 오른다.
     */
    static final long ROOM_ALIVE_RECHECK_INTERVAL_NANOS = Duration.ofSeconds(30).toNanos();




    /**
     * transport 전용: 레이트리밋에 걸렸다는 사실과 해제 시각을 이 세션에 적어둔다.
     *
     * <p>거부 자체는 레이트리밋이 하지만, <b>거부하는 비용</b>은 제한하지 못한다 — 거부 한 번에 Redis 왕복이
     * 세 번(강퇴·밴 조회·레이트리밋 스크립트) 든다. 회선 속도로 밀면 그 비용이 그대로 반복된다.
     * 해제 시각을 알고 있는 동안은 같은 답을 다시 물을 이유가 없으므로 여기에 적어두고 로컬에서 끊는다.
     */
    public void recordRateLimited(String sessionId, long retryAfterSeconds) {
        LocalSession ls = index.get(sessionId);
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
        LocalSession ls = index.get(sessionId);
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

        boolean admitted = slots.tryAcquire(session.userId(), session.roomId(), sessionId);
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
            if (!admitted) {
                long capped = slots.recordCapped();
                if (slots.shouldLogCapped()) {
                    // 표본 하나와 Pod 누적을 함께 적는다 — 드롭 로그와 같은 표기다. 이 줄이 늘어나면
                    // 공격이 아니라 상한이 낮은 것일 수 있으니, 사람이 그 둘을 가를 수 있게 둘 다 남긴다.
                    log.warn("동시 세션 상한 초과로 입장 거부 — 표본 userId={} 표본 roomId={} 상한={} 누적거부={}",
                            session.userId(), session.roomId(), slots.max(), capped);
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
            index.put(sessionId, pending);

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

            index.addToRoom(session.roomId(), sessionId);
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
                slots.release(session.userId(), session.roomId(), sessionId);
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
        LocalSession ls = index.get(sessionId);
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
        LocalSession leaving = index.get(sessionId);
        UUID indexRoom = leaving != null ? leaving.session().roomId() : roomId;
        if (leaving != null) {
            slots.release(leaving.session().userId(), leaving.session().roomId(), sessionId);
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
    public Mono<Void> sendToSession(String sessionId, OutboundMessage message) {
        return Mono.fromRunnable(() -> outbound.emit(index.get(sessionId), new OutboundFrame(message)));
    }

    @Override
    public Mono<Void> sendToRoomLocal(UUID roomId, OutboundMessage message) {
        return Mono.fromRunnable(() -> {
            SessionOutbound.FanOutBudget budget = outbound.newSharedBudget();
            // 전원이 같은 메시지를 받는다 — 프레임 하나를 공유하므로 직렬화도 한 번이다.
            OutboundFrame frame = new OutboundFrame(message);
            index.forEachInRoom(roomId, ls -> outbound.emit(ls, frame, budget));
        });
    }

    @Override
    public Mono<Void> sendToRoomGated(UUID roomId, Function<ChatSession, OutboundMessage> resolver) {
        // 세션별 차등 fan-out — resolver가 세션마다 메시지 생성(방주인 PII 게이팅·kick 분기). null이면 그 세션 skip.
        return Mono.fromRunnable(() -> {
            SessionOutbound.FanOutBudget budget = outbound.newSharedBudget();
            // resolver 가 돌려주는 서로 다른 메시지마다 프레임 하나 — 같은 메시지를 받는 세션들은
            // 그 프레임을 공유하므로 직렬화가 세션 수가 아니라 <b>종류 수</b>에 비례한다.
            // 동치가 아니라 동일성으로 묶는다: fanOutChat 이 뷰 4종을 미리 만들어 재사용하므로
            // 동일성으로 충분하고, record equals 는 15 필드를 비교하므로 이쪽이 싸다.
            //
            // ⚠️ "빗나가도 직렬화 한 번 더 하는 것뿐" 이라고 적었는데 <b>틀렸다</b>. 이 맵은
            // SessionOutbound.DEFAULT_CONTENTION_SPIN_NANOS javadoc 이 "예산 소비자를 더 붙이지 말라" 고 못 박은 바로 그 루프 안에 있다 —
            // 12,000 세션 fan-out 이 이미 예산의 79%(3.96ms/5ms)다. 계약이 지켜지면 엔트리 4개라
            // 무시할 값이지만, 빗나가면 12,000 distinct 엔트리(table 65,536 · refs 약 524KB)로
            // 실측 +0.94ms 가 그 위에 얹힌다. 그때 꼬리 세션은 재시도 없이 한 번만 시도하고,
            // 결과는 조용한 유실이 아니라 metrics.deliveryDropped(SESSION_EMIT_CONTENTION) 로
            // 세어지는 드롭이다(세션은 끊기지 않는다 — consumerCannotKeepUp 이 그 결과를 안 본다).
            // 그래서 계약은 javadoc 이 아니라 테스트가 지킨다
            // (ChatBroadcastSubscriberTest.theResolverReturnsTheSameInstancePerView).
            Map<OutboundMessage, OutboundFrame> frames = new IdentityHashMap<>(4);
            index.forEachInRoom(roomId, ls -> {
                OutboundMessage message = resolver.apply(ls.session());
                if (message != null) {
                    outbound.emit(ls, frames.computeIfAbsent(message, OutboundFrame::new), budget);
                }
            });
        });
    }

    @Override
    public Mono<Void> closeUser(UUID roomId, UUID userId) {
        // 강퇴는 정책상 종료라 1008 — 프론트가 "재접속하지 말 것"으로 읽는 코드다.
        return Mono.fromRunnable(() -> {
            SessionOutbound.FanOutBudget budget = outbound.newSharedBudget();
            index.forEachInRoom(roomId, ls -> {
                if (ls.session().userId().equals(userId)) {
                    metrics.sessionCut(SessionCutReason.MODERATION);
                    outbound.terminate(ls, CloseStatus.POLICY_VIOLATION, budget.completeDeadline());
                }
            });
        });
    }

    @Override
    public Mono<Void> sendToUserEverywhere(UUID userId, OutboundMessage message) {
        return Mono.fromRunnable(() -> {
            SessionOutbound.FanOutBudget budget = outbound.newSharedBudget();
            // 한 사람의 모든 탭이 같은 메시지를 받는다 — 위와 같은 이유로 프레임 하나를 공유한다.
            OutboundFrame frame = new OutboundFrame(message);
            index.forEachSessionOf(userId, ls -> outbound.emit(ls, frame, budget));
        });
    }

    @Override
    public Mono<Void> closeUserEverywhere(UUID userId, SessionCutReason reason) {
        // 밴도 탈퇴도 1008 이다 — 프론트가 "재접속하지 말 것"으로 읽는다. 와이어가 같으니 둘을
        // 가르는 것은 사유 태그뿐이고, 그래서 호출자가 그것을 넘긴다(제재율과 탈퇴율은 다른 수다).
        return Mono.fromRunnable(() -> {
            SessionOutbound.FanOutBudget budget = outbound.newSharedBudget();
            index.forEachSessionOf(userId, ls -> {
                metrics.sessionCut(reason);
                outbound.terminate(ls, CloseStatus.POLICY_VIOLATION, budget.completeDeadline());
            });
        });
    }


    @Override
    public Mono<Void> closeAll(UUID roomId) {
        // 방 종료는 정상 종료(1000). 사유는 앞서 보낸 SYSTEM(ROOM_ENDED)이 전달한다.
        return Mono.fromRunnable(() -> {
            SessionOutbound.FanOutBudget budget = outbound.newSharedBudget();
            index.forEachInRoom(roomId, ls -> {
                metrics.sessionCut(SessionCutReason.ROOM_ENDED);
                outbound.terminate(ls, CloseStatus.NORMAL, budget.completeDeadline());
            });
        });
    }

    /** transport 전용: 핸들러가 강제 종료 경로에 연결할 제어 신호. 모르는 세션이면 영영 발화하지 않는다. */
    public Mono<CloseStatus> terminationSignal(String sessionId) {
        LocalSession ls = index.get(sessionId);
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
        LocalSession ls = index.get(sessionId);
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
        LocalSession ls = index.get(sessionId);
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
        LocalSession ls = index.get(sessionId);
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
        LocalSession ls = index.get(sessionId);
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
        LocalSession ls = index.get(sessionId);
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
        LocalSession ls = index.get(sessionId);
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
        LocalSession ls = index.get(sessionId);
        if (ls == null) {
            return false;
        }
        return System.nanoTime() - ls.lastInboundNanos().get() >= deadline.toNanos();
    }

    /** 생존 시계를 과거로 밀어 "응답 없음" 상태를 만든다. (테스트 진입점 — 기한을 기다리지 않기 위해) */
    void expireLiveness(String sessionId, Duration deadline) {
        LocalSession ls = index.get(sessionId);
        if (ls != null) {
            ls.lastInboundNanos().addAndGet(-deadline.toNanos());
        }
    }

    /** 마지막 확인 시각을 과거로 밀어 창이 열린 상태를 만든다. (테스트 진입점 — 30초를 기다리지 않기 위해) */
    void expireRoomAliveWindow(String sessionId) {
        LocalSession ls = index.get(sessionId);
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
        LocalSession ls = index.get(sessionId);
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
        LocalSession ls = index.get(sessionId);
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
        LocalSession ls = index.get(sessionId);
        if (ls == null) {
            return;
        }
        log.warn("전송 경로에서 강퇴 확인 — 세션 종료(종료 신호를 놓친 Pod) sessionId={} roomId={} userId={}",
                sessionId, ls.session().roomId(), ls.session().userId());
        metrics.sessionCut(SessionCutReason.MODERATION);
        outbound.terminate(ls, CloseStatus.POLICY_VIOLATION, outbound.newBudget().completeDeadline());
    }

    /**
     * transport 전용: 방이 끝난 것으로 확인된 세션을 닫는다.
     *
     * <p>정상 종료(1000)로 닫는다 — {@link #closeAll}과 같은 코드다. 이 경로는 종료 신호를 놓친 Pod가
     * 뒤늦게 같은 사실을 알게 된 것뿐이라, 클라이언트 입장에서 정상 종료와 달라야 할 이유가 없다.
     * 사유는 앞서 보낸 SYSTEM(ROOM_ENDED)이 전달한다(정상 경로와 동일).
     */
    public void terminateRoomEnded(String sessionId) {
        LocalSession ls = index.get(sessionId);
        if (ls == null) {
            return;
        }
        metrics.sessionCut(SessionCutReason.ROOM_ENDED);
        log.warn("전송 경로에서 방 종료 확인 — 세션 종료(종료 신호를 놓친 Pod) sessionId={} roomId={}",
                sessionId, ls.session().roomId());
        outbound.terminate(ls, CloseStatus.NORMAL, outbound.newBudget().completeDeadline());
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
     * ({@link SessionOutbound#emit}의 1013 주석 참고) 여기서 원하는 것이 바로 그것이다. 사유 프레임은 보내지 않는다 —
     * 클라가 해야 할 일이 재접속 하나뿐이라 알려 줄 것이 없고, 새 SYSTEM 코드를 만들면 프론트 계약이
     * 늘어난다.
     */
    public void terminateLifetimeExpired(String sessionId) {
        LocalSession ls = index.get(sessionId);
        if (ls == null) {
            return;
        }
        // 솎아낸다. 같이 들어온 세션들이 같이 만료되므로 이 로그는 재접속 폭주 구간에 몰리고,
        // 그 구간에 세션당 한 줄을 남기면 원인 로그를 제 로그로 묻는다 — 이 파일의 다른 열화 로그가
        // 전부 간격 솎기를 쓰는 근거와 같다. 누적 수는 함께 남겨 규모를 잃지 않는다.
        metrics.sessionCut(SessionCutReason.LIFETIME);
        long expired = lifetimeExpiredCount.incrementAndGet();
        if (lifetimeExpiryLog.tryAcquire()) {
            log.info("세션 수명 상한 — 종료(재접속으로 권위 재평가) 표본 sessionId={} 표본 roomId={} 누적={}",
                    sessionId, ls.session().roomId(), expired);
        }
        outbound.terminate(ls, CloseStatus.NORMAL, outbound.newBudget().completeDeadline());
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
        LocalSession ls = index.get(sessionId);
        if (ls == null) {
            return;
        }
        metrics.sessionCut(SessionCutReason.LIVENESS);
        log.info("생존 확인 실패 — 세션 종료(응답 없는 소켓 회수) sessionId={} roomId={}",
                sessionId, ls.session().roomId());
        outbound.terminate(ls, CloseStatus.NORMAL, outbound.newBudget().completeDeadline());
    }

    /** transport 전용: 이 세션을 어떤 코드로 닫을지. 서버가 정한 사유가 없으면(클라가 먼저 끊는 등) 정상 종료. */
    public CloseStatus closeStatusOf(String sessionId) {
        LocalSession ls = index.get(sessionId);
        CloseStatus status = ls == null ? null : ls.closeStatus().get();
        return status == null ? CloseStatus.NORMAL : status;
    }

    /** 자리 맵의 (유저, 방) 항목 수. (테스트 진입점 — 자리가 다 비면 항목이 사라지는지 확인용) */
    int trackedSlotCount() {
        return slots.trackedSlotKeys();
    }


    /** 인덱스가 추적 중인 방 수. (테스트 진입점 — 방이 비면 항목이 사라지는지 확인용) */
    int trackedRoomCount() {
        return index.roomCount();
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
     *
     * <p>이 레지스트리의 힙은 용량 상한을 정하는 자원이 아니다 — 실측(프로세스 안, 20,000 세션,
     * register/unregister 3주기): 유휴 세션당 약 1,990 B, 12,000 세션에 약 24 MB, 전원 퇴장 후 잔존
     * 0 MB(주기마다 누수 없음). 그래서 용량 상한의 수는 이 측정이 못 보는 축(Netty·TLS 버퍼, 파일
     * 디스크립터, 팬아웃 CPU)에서, 즉 실제 Pod 부하 측정에서 나와야 한다.
     *
     * <p>⚠️ 다시 잴 때 협력자를 mock 으로 두지 말 것. 처음 측정은 주기마다 15 MB 가 남아 선형으로
     * 늘어 세션 누수처럼 보였는데, 원인은 Mockito 가 스텁 저장소에 쌓은 호출 기록 40,000 건이었다.
     * 손으로 쓴 스텁으로 바꾸자 잔존이 0 이 됐다.
     */
    public int localSessionCount() {
        return index.sessionCount();
    }

    /** 이 Pod 가 중계 중인 방 수 — 게이지용. 세션 수와 함께 봐야 "방당 세션" 이 나온다. */
    public int localRoomCount() {
        return index.roomCount();
    }


    /**
     * {@code local} 과 방 색인(과 그 수명을 따르는 캐시 둘)을 되돌린다. <b>자리는 건드리지 않는다.</b>
     *
     * <p>퇴장과 <b>등록 실패 보상</b>이 같은 걷기를 쓰도록 한 곳에 둔다. 둘이 따로 있으면 한쪽만
     * 빠뜨리는 변경이 조용히 들어온다 — 실제로 그랬다: 보상이 자리만 되돌리던 동안 {@code local}
     * 항목이 남았다 — 호출부의 {@code doFinally} 는 돌지만 그것이 부르는 {@code unregister} 가
     * {@code local.get} 으로 키를 만들어 자리를 못 걷는다(테스트가 7건 누출로 잡았다).
     *
     * <p>⚠️ <b>자리를 여기 두지 않는 이유가 있다.</b> 한때 이 메서드가 자리까지 되돌렸고 그 키를
     * {@code index.get(sessionId)} 으로 만들었다. 그러면 자리 반납이 "{@code local} 에 항목이 있을
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
        index.removeSession(sessionId, roomId, () -> viewerCounts.forgetRoom(roomId));
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
        return slots.slotsReaped();
    }

    /**
     * 동시 세션 상한으로 거부한 누적 수.
     *
     * <p>{@code public} 인 이유는 지표 배선이 쓰기 때문이다({@code ChatSessionGaugeConfig}). 집행이
     * 보이지 않으면 "상한이 공격을 막고 있다" 와 "상한이 정상 시청자를 막고 있다" 를 가를 수 없고,
     * 후자는 우리가 만든 장애다 — 그래서 집행과 그 관측을 같은 커밋에 둔다.
     */
    public long admissionCapped() {
        return slots.admissionCapped();
    }

    /** fan-out 공유 예산 누적 수 — 불변식 고정용 테스트 진입점({@link SessionOutbound#budgetsCreated}). */
    long budgetsCreated() {
        return outbound.budgetsCreated();
    }

    /** 버퍼 초과 판정 — 정책을 테스트로 고정하기 위한 진입점({@link SessionOutbound#consumerCannotKeepUp}). */
    boolean consumerCannotKeepUp(Sinks.EmitResult result) {
        return outbound.consumerCannotKeepUp(result);
    }

    /** 경합으로 버린 메시지 누적 수 — 테스트 진입점({@link SessionOutbound#droppedOnContention}). */
    long droppedOnContention() {
        return outbound.droppedOnContention();
    }

    /** Pod 당 하나인 재등록 타이머를 켠다 — 주기·흡수 규칙은 {@link ViewerCounts#startHeartbeat}. */
    @PostConstruct
    void startHeartbeat() {
        viewerCounts.startHeartbeat();
    }

    @PreDestroy
    void stopHeartbeat() {
        viewerCounts.stopHeartbeat();
    }

    /** 한 세션의 재등록(테스트 진입점) — {@link ViewerCounts#reregister}. */
    Mono<Void> reregister(LocalSession ls) {
        return viewerCounts.reregister(ls);
    }

    /**
     * 재등록 실패 누적 — {@code public} 인 이유는 {@code ChatSessionGaugeConfig}(다른 패키지)가
     * {@code chat.sessions.heartbeat_failures} 를 배선하기 때문이다. 0 이 건강을 뜻하지 않는 이유는
     * {@link ViewerCounts#heartbeatFailures}.
     */
    public long heartbeatFailures() {
        return viewerCounts.heartbeatFailures();
    }

    /** 입장 ROOM_INFO 를 보낸 <b>뒤</b> 부른다 — 순서가 왜 그런지는 {@link ViewerCounts#forgetLastPushedCount}. */
    void forgetLastPushedCount(UUID roomId) {
        viewerCounts.forgetLastPushedCount(roomId);
    }

    /** 시청자 수 갱신 한 회차(테스트 진입점) — {@link ViewerCounts#pushViewerCounts}. */
    Mono<Void> pushViewerCounts() {
        return viewerCounts.pushViewerCounts();
    }

    /** 마지막으로 보낸 수를 들고 있는 방의 개수 — 누수 확인용(테스트 진입점). */
    int trackedViewerCounts() {
        return viewerCounts.trackedViewerCounts();
    }

    @Override
    public Mono<Long> getActiveCount(UUID roomId) {
        return viewerCounts.getActiveCount(roomId);
    }

    /** 진행 중인 조회 합치기 항목 수(테스트 진입점). */
    int trackedInFlightCounts() {
        return viewerCounts.trackedInFlightCounts();
    }

    /**
     * 자리가 꽉 찼을 때만 그 (유저, 방)의 응답 없는 세션 자리를 거둔다 — 거둔 수를 돌려준다. 자리만
     * 비우고 종료하지 않는 이유와 그 창은 {@link SessionSlots#reapUnresponsiveIfFull}.
     */
    public int reapUnresponsiveIfFull(UUID userId, UUID roomId, Duration livenessDeadline) {
        return slots.reapUnresponsiveIfFull(userId, roomId, livenessDeadline);
    }
}
