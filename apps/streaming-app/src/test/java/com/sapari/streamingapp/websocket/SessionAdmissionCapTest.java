package com.sapari.streamingapp.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatSessionRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;

import reactor.core.publisher.Mono;

/**
 * 같은 (유저, 방)의 동시 세션 상한.
 *
 * <p>막는 것은 "유효한 룸 토큰 한 장으로 세션을 계속 여는 것" 이다. 룸 토큰은 단건용이 아니라 TTL
 * (약 90초) 안에서 재사용 가능하므로 그 창 안에 같은 토큰으로 몇 번이고 핸드셰이크할 수 있다.
 *
 * <p>여기서 재는 축이 넷이다 — <b>막는가 · 거부 사유가 맞는가 · 퇴장이 자리를 돌려주는가 ·
 * 동시 접속에서도 넘지 않는가</b>. 셋째가 가장 중요하다: 퇴장이 인덱스를 걷지 않으면 그 유저는 그
 * 방에 <b>다시 들어올 수 없다</b>(상한이 영구히 찬 상태로 고정된다). 상한 코드가 만들 수 있는 최악의
 * 결함이 "공격을 못 막는 것" 이 아니라 "정상 사용자를 영구히 막는 것" 이라, 그쪽을 반드시 고정한다.
 */
@DisplayName("동시 세션 상한 — (유저, 방) 단위")
class SessionAdmissionCapTest {

    /** 반응형 종결자 상한 — 없으면 회귀가 테스트를 실패시키는 대신 멈추게 한다. */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    private static final int CAP = ChatSessionRegistry.MAX_SESSIONS_PER_USER_PER_ROOM;

    /**
     * reap 에 넘기는 기한 — 운영 기본값의 <b>기한 + 간격</b>(90s + 30s)이다.
     *
     * <p>기한만 쓰면 안 된다: 생존 루프가 첫 평가를 그만큼 늦추므로, reap 이 기한만으로 판정하면
     * 그 격자에서는 살아남는 구간의 멀쩡한 세션을 죽인다. 핸들러가 넘기는 값과 같아야 한다.
     */
    private static final Duration REAP_DEADLINE = Duration.ofSeconds(120);

    private ChatSessionRepository sessionRepository;
    private ChatMetrics metrics;
    private ChatSessionRegistry registry;

    private final UUID roomId = UUID.randomUUID();
    private final UUID otherRoomId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ChatSessionRepository.class);
        given(sessionRepository.add(any(), any(), any())).willReturn(Mono.empty());
        given(sessionRepository.remove(any(), any())).willReturn(Mono.empty());
        // NOOP 이 아니라 목이다 — 종료가 일어났는지는 이 포트로만 보인다(위 reap 테스트 참고).
        metrics = mock(ChatMetrics.class);
        registry = new ChatSessionRegistry(sessionRepository, new ChatPermissionPolicy(), metrics);
    }

    private ChatSession session(UUID room, UUID user) {
        return new ChatSession(room, user, ChatRole.BUYER, "닉", "e@example.com", false);
    }

    private void admit(String sid, UUID room, UUID user) {
        registry.register(sid, session(room, user)).block(VERIFY_CEILING);
    }

    private void fillToCap() {
        for (int i = 0; i < CAP; i++) {
            admit("s" + i, roomId, userId);
        }
    }

    @Test
    @DisplayName("⭐ 상한을 넘는 접속을 거부한다 — 토큰이 TTL 안에서 재사용 가능해 창이 열려 있다")
    void theCapRejectsTheOneOverTheLimit() {
        fillToCap();
        assertThat(registry.localSessionCount()).isEqualTo(CAP);

        assertThatThrownBy(() -> admit("overflow", roomId, userId))
                .isInstanceOf(EntryDeniedException.class)
                .extracting(e -> ((EntryDeniedException) e).reason())
                // 사유가 맞아야 클라가 강퇴·밴과 다른 문구를 낸다 — close 코드는 셋 다 1008 이다
                .isEqualTo(EntryDeniedException.Reason.TOO_MANY_SESSIONS);

        assertThat(registry.localSessionCount())
                .as("거부된 세션이 local 에 들어갔다 — 게이지와 시청자 수가 그만큼 틀린다")
                .isEqualTo(CAP);
        assertThat(registry.admissionCapped())
                .as("집행이 보이지 않으면 상한이 공격을 막는지 정상 시청자를 막는지 가를 수 없다")
                .isEqualTo(1);

        // 거부가 Redis 명부를 건드리지 않는지. 명부에 올라가면 시청자 수가 거부된 연결만큼 부풀고,
        // 거부된 세션은 local 에 없어 unregister 의 정리를 건너뛰므로 그 과다계수가 TTL 까지 남는다.
        then(sessionRepository).should(never()).add(eq(roomId), eq("overflow"), eq(userId));

        // 방 인덱스(roomSessions)는 여기서 단언하지 않는다 — 단언할 수 있는 모양이 아니다.
        // 상한 거부는 정의상 "그 방에 그 유저 세션이 상한만큼 있다" 는 뜻이라 방 항목이 이미 존재하고,
        // trackedRoomCount 는 어느 쪽으로 구현해도 1 이다(그래서 그 수를 재는 단언은 통과만 하고
        // 아무것도 잡지 못한다). 집합 <b>안</b>에 죽은 sessionId 가 남는 모양은 공개 표면으로 보이지
        // 않는다 — 팬아웃이 local 조회로 그것을 건너뛰므로 동작으로도 드러나지 않는다(누수일 뿐이다).
        // 지금 그 경로가 없는 근거는 코드다: 거부 분기가 roomSessions.compute 앞에서 돌아간다.
    }

    @Test
    @DisplayName("⭐ 퇴장이 자리를 돌려준다 — 안 걷으면 그 유저가 그 방에 영구히 못 들어온다")
    void leavingGivesTheSlotBack() {
        fillToCap();
        registry.unregister(roomId, "s0").block(VERIFY_CEILING);

        // assertThat(admissionCapped()) 로 쓰면 영원히 안 보이는 단언이 된다 — 거부가 생기면
        // 그 전에 admit 이 EntryDeniedException 으로 던져 공들인 사유 문구가 출력되지 않는다.
        assertThatCode(() -> admit("reconnected", roomId, userId))
                .as("자리가 비었는데 거부됐다 — 퇴장이 (유저, 방) 인덱스를 걷지 않았다")
                .doesNotThrowAnyException();

        assertThat(registry.localSessionCount()).isEqualTo(CAP);
        assertThat(registry.admissionCapped()).isZero();
    }

    @Test
    @DisplayName("⭐ 죽은 소켓이 들고 있던 자리는 거둔다 — 모바일 핸드오버가 상한에 걸리지 않게")
    void deadSlotsAreReapedBeforeTheCapRejects() {
        // 상한만큼 붙은 뒤 전부 조용히 끊긴 상태를 만든다. 생존 확인의 첫 점검은 기한 + 간격
        // (기본 약 2분) 뒤라, 그 창 안의 재접속은 "살아 있는 자리" 로 오판된 죽은 자리에 막힌다.
        fillToCap();
        for (int i = 0; i < CAP; i++) {
            registry.expireLiveness("s" + i, REAP_DEADLINE);
        }

        assertThat(registry.reapUnresponsiveIfFull(userId, roomId, REAP_DEADLINE))
                .as("죽은 자리를 거두지 않았다 — 그러면 재접속이 2분간 거부된다").isEqualTo(CAP);

        admit("reconnected", roomId, userId);
        assertThat(registry.admissionCapped())
                .as("자리를 거뒀는데도 거부됐다").isZero();
        assertThat(registry.slotsReaped())
                .as("거둔 수가 지표에 안 들어오면 공격과 모바일 핸드오버를 가를 수 없다").isEqualTo(CAP);

        // ⭐ 거둔 세션을 <b>종료하지는 않는다.</b> 종료는 Lettuce NIO 이벤트루프에서 세션마다
        // 50ms 예산을 새로 받는 spin 이라, 5건이면 그 루프가 최대 250ms 멈춘다 — 이 파일이 두 자리에서
        // 금지한 모양이고 하필 발동 조건이 재접속 폭주 구간이다. 수거는 각 세션의 생존 확인
        // (Schedulers.parallel)이 다음 틱에 가져간다.
        //
        // ⚠️ 이걸 localSessionCount 로 재면 <b>변이에 반응하지 않는다</b> — terminateUnresponsive 는
        // local 에서 지우지 않으므로(지우는 것은 핸들러 cleanup → unregister 다) 종료를 다시 넣어도
        // 그 수가 그대로다. 실제로 한때 그렇게 적어 뒀고, reap 에 종료를 되돌려도 전 스위트가
        // 초록이었다 — 금지한다고 적은 변경이 통째로 통과했다. 종료의 관측 가능한 흔적은 지표다.
        then(metrics).should(never()).sessionCut(any());
    }

    /**
     * 등록이 중간에 던져도 자리가 새지 않는다.
     *
     * <p>이게 상한 코드가 만들 수 있는 <b>최악의 결함</b>이다 — 자리가 남으면 회수 경로 둘이 다 못
     * 걷는다(퇴장은 {@code local.get} 이 null 이라 건너뛰고, 수거는 {@code local} 에 없는 세션에
     * "살아 있음" 을 준다). 그 유저는 프로세스 재시작 전까지 그 방에 못 들어온다.
     *
     * <p><b>주입 협력자로 던진다.</b> 한때 가드가 {@code local.put} 하나만 감쌌는데 그러면 집행
     * 대상이 사실상 OOM 뿐이라 테스트로 밟을 수 없었다. 가드를 자리 추가 직후부터 반환까지 넓히면
     * 감사 블록이 그 안에 들어오고, 그 블록이 부르는 정책이 테스트가 쥘 수 있는 유일한 시임이 된다 —
     * 가드를 넓힌 것이 테스트 가능성까지 같이 산 셈이다.
     */
    @Test
    @DisplayName("⭐ 등록이 중간에 던져도 자리가 새지 않는다 — 새면 그 유저가 그 방에 영구히 못 들어온다")
    void aThrowMidRegistrationDoesNotLeakTheSlot() {
        ChatPermissionPolicy throwingPolicy = mock(ChatPermissionPolicy.class);
        given(throwingPolicy.usesPrivilegedViewInSomeoneElsesRoom(any(), anyBoolean()))
                .willThrow(new IllegalStateException("프로브: 등록 중간에 던진다"));
        ChatSessionRegistry guarded =
                new ChatSessionRegistry(sessionRepository, throwingPolicy, metrics);

        // 상한보다 많이 시도한다 — 자리가 새면 상한만큼 찬 뒤부터 사유가 TOO_MANY_SESSIONS 로 바뀐다.
        for (int i = 0; i < CAP + 2; i++) {
            String sid = "leak" + i;
            assertThatThrownBy(() -> guarded.register(sid, session(roomId, userId)).block(VERIFY_CEILING))
                    .as("자리가 새어 거부로 바뀌었다 — 던진 등록이 자리를 남겼다")
                    .isInstanceOf(IllegalStateException.class);
        }

        assertThat(guarded.admissionCapped())
                .as("던진 등록이 자리를 남겨 상한이 찼다").isZero();
        assertThat(guarded.localSessionCount())
                .as("던진 등록이 local 에 세션을 남겼다").isZero();
    }

    @Test
    @DisplayName("⭐ 퇴장이 세션 자신의 방으로 자리를 걷는다 — 인자를 믿으면 영구 차단이 된다")
    void leavingUsesTheSessionsOwnRoomForTheSlotKey() {
        // 등재는 session.roomId() 로 하는데 unregister 는 인자 roomId 를 받는다. 포트 계약
        // (ChatSessionManager.unregister)에 둘이 같아야 한다는 보장이 없어, 키를 인자로 만들면
        // 어긋나는 호출 하나가 자리를 영구히 남긴다 — 그 유저는 프로세스 재시작 전까지 그 방에
        // 못 들어온다. 오늘 호출부는 하나이고 값이 같지만, 그 사실에 기대지 않는다.
        fillToCap();
        registry.unregister(UUID.randomUUID(), "s0").block(VERIFY_CEILING);   // 엉뚱한 방을 넘긴다

        assertThatCode(() -> admit("reconnected", roomId, userId))
                .as("어긋난 roomId 로 퇴장하자 자리가 새어 영구히 찬 상태가 됐다")
                .doesNotThrowAnyException();
        assertThat(registry.admissionCapped()).isZero();
    }

    @Test
    @DisplayName("⭐ 살아 있는 자리는 절대 밀어내지 않는다 — 밀어내면 남의 세션을 끊는 능력이 생긴다")
    void liveSlotsAreNeverEvicted() {
        // evict-oldest 를 기각한 이유가 이것이다. 룸 토큰은 TTL 안에서 재사용 가능하므로, 자리를
        // 비우는 근거가 "오래됨" 이면 토큰 한 장으로 정당한 사용자의 세션을 끊을 수 있다.
        // 여기서 자리를 비우는 유일한 근거는 "그 소켓은 이미 죽었다" 다.
        fillToCap();

        assertThat(registry.reapUnresponsiveIfFull(userId, roomId, REAP_DEADLINE))
                .as("살아 있는 세션을 밀어냈다 — 토큰 재사용이 세션 종료 능력이 된다").isZero();
        assertThat(registry.localSessionCount()).isEqualTo(CAP);

        assertThatThrownBy(() -> admit("overflow", roomId, userId))
                .as("자리를 비우지 못했으면 거부가 맞다")
                .isInstanceOf(EntryDeniedException.class);
    }

    @Test
    @DisplayName("자리가 꽉 차지 않았으면 거두지 않는다 — 접속마다 도는 자리라 비용을 두지 않는다")
    void nothingIsReapedWhileSlotsRemain() {
        admit("s0", roomId, userId);
        registry.expireLiveness("s0", REAP_DEADLINE);

        assertThat(registry.reapUnresponsiveIfFull(userId, roomId, REAP_DEADLINE))
                .as("자리가 남았는데 죽은 세션을 거뒀다 — 수거는 생존 확인의 몫이다").isZero();
        assertThat(registry.localSessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 유저의 다른 방 · 같은 방의 다른 유저는 막지 않는다 — 단위가 (유저, 방)이다")
    void theCapIsScopedToTheUserAndRoomPair() {
        fillToCap();

        // 방을 여러 개 보는 관리자를 막지 않는다. 유저 단위로 넓히면 여기서 막힌다.
        admit("otherRoom", otherRoomId, userId);
        // 인기 방의 정상 시청자를 막지 않는다. 방 단위로 넓히면 여기서 막힌다.
        admit("otherUser", roomId, otherUserId);

        assertThat(registry.admissionCapped()).isZero();
        assertThat(registry.localSessionCount()).isEqualTo(CAP + 2);
    }

    /**
     * 검사와 등재가 한 원자 구간인지. <b>라운드를 여러 번, 스레드를 상한의 10배로</b> 도는 이유는
     * 탐지율이다.
     *
     * <p>처음에는 1라운드 × 20스레드였고, 되돌림 확인에 {@code Thread.sleep(5)} 를 끼워 창을 넓혀
     * 쟀다. 그 모양은 20개가 전부 통과해 {@code was: 20} 이 결정론적으로 나오지만, <b>현실적인
     * 회귀</b>(sleep 없는 순수 분리)는 그렇지 않다. 그래서 되돌림이 통과한 근거가 실제 회귀보다
     * 쉬운 변형이었고, "원자 구간이 테스트로 묶였다" 가 실제보다 강하게 읽혔다. 순수 분리를 심고
     * 형상별로 12회씩 재서 고른 값이다:
     * <pre>
     * 1라운드 ×  20스레드   RED  5 / GREEN 7   (탐지 42%, 서명 was: 6)
     * 4라운드 ×  50스레드   RED 10 / GREEN 2   (탐지 83%)
     * 12라운드 × 50스레드   RED 12 / GREEN 0   (탐지 12/12, 소요 약 4초)
     * </pre>
     *
     * <p>라운드마다 방을 새로 쓰는 것은 앞 라운드가 자리를 채워 둬 뒤 라운드가 그냥 거부되는 것을
     * 막기 위해서다. 12/12 라도 원리상 <b>확정 게이트가 아니라 탐지기</b>다 — 경쟁 창이 실제로
     * 열리는지는 스케줄러에 달려 있다. 라운드를 줄이면 그만큼 탐지가 떨어진다는 것이 위 표다.
     */
    @Test
    @DisplayName("⭐ 동시 접속에서도 상한을 넘지 않는다 — 세고 나중에 넣으면 둘 다 통과한다")
    void concurrentHandshakesDoNotExceedTheCap() throws Exception {
        int rounds = 12;
        int threads = CAP * 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "admission-race");
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (int round = 0; round < rounds; round++) {
                UUID racedRoom = UUID.randomUUID();
                int before = registry.localSessionCount();
                // 같은 순간에 밀어 넣는다 — 검사와 등재가 쪼개져 있으면 여럿이 같은 수를 읽고 다 통과한다.
                CountDownLatch startLine = new CountDownLatch(1);
                CountDownLatch finished = new CountDownLatch(threads);
                ConcurrentLinkedQueue<String> denied = new ConcurrentLinkedQueue<>();
                for (int i = 0; i < threads; i++) {
                    String sid = "race" + round + "-" + i;
                    pool.execute(() -> {
                        try {
                            // 상한을 건다 — 없으면 시작 래치가 안 열릴 때 워커가 영구히 남는다.
                            if (!startLine.await(VERIFY_CEILING.toSeconds(), TimeUnit.SECONDS)) {
                                return;
                            }
                            admit(sid, racedRoom, userId);
                        } catch (EntryDeniedException e) {
                            denied.add(sid);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            finished.countDown();
                        }
                    });
                }
                startLine.countDown();
                assertThat(finished.await(VERIFY_CEILING.toSeconds(), TimeUnit.SECONDS))
                        .as("경쟁 접속이 상한 안에서 끝나지 않았다 (라운드 %d)", round).isTrue();

                assertThat(registry.localSessionCount() - before)
                        .as("동시 접속이 상한을 넘겼다 — 검사와 등재가 한 원자 구간이 아니다 (라운드 %d)", round)
                        .isEqualTo(CAP);
                assertThat(denied).hasSize(threads - CAP);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
