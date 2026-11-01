package com.sapari.streamingapp.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    private ChatSessionRepository sessionRepository;
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
        registry = new ChatSessionRegistry(sessionRepository, new ChatPermissionPolicy(), ChatMetrics.NOOP);
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

        admit("reconnected", roomId, userId);

        assertThat(registry.localSessionCount()).isEqualTo(CAP);
        assertThat(registry.admissionCapped())
                .as("자리가 비었는데 거부됐다 — 퇴장이 (유저, 방) 인덱스를 걷지 않았다").isZero();
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

    @Test
    @DisplayName("⭐ 동시 접속에서도 상한을 넘지 않는다 — 세고 나중에 넣으면 둘 다 통과한다")
    void concurrentHandshakesDoNotExceedTheCap() throws Exception {
        int threads = CAP * 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "admission-race");
            thread.setDaemon(true);
            return thread;
        });
        try {
            // 같은 순간에 밀어 넣는다 — 검사와 등재가 쪼개져 있으면 여럿이 같은 수를 읽고 다 통과한다.
            CountDownLatch startLine = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(threads);
            ConcurrentLinkedQueue<String> denied = new ConcurrentLinkedQueue<>();
            for (int i = 0; i < threads; i++) {
                String sid = "race" + i;
                pool.execute(() -> {
                    try {
                        startLine.await();
                        admit(sid, roomId, userId);
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
                    .as("경쟁 접속이 상한 안에서 끝나지 않았다").isTrue();

            assertThat(registry.localSessionCount())
                    .as("동시 접속이 상한을 넘겼다 — 검사와 등재가 한 원자 구간이 아니다")
                    .isEqualTo(CAP);
            assertThat(denied).hasSize(threads - CAP);
        } finally {
            pool.shutdownNow();
        }
    }
}
