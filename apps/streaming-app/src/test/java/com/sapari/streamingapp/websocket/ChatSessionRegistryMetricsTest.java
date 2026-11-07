package com.sapari.streamingapp.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.application.port.SessionCutReason;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatSessionRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.infrastructure.metrics.MicrometerChatMetrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Mono;

/**
 * 서버가 세션을 끊은 <b>사유</b>가 밖으로 나가는지 본다.
 *
 * <p>이 지표가 존재하는 이유는 와이어에서 구분되지 않기 때문이다 — 방 종료·수명 만료·생존 실패가
 * 전부 close 1000 이고, 그중 방 종료만 직전에 설명 프레임을 보낸다. 밖에서 보면 "정상 종료가 늘었다"
 * 로만 보이는데 대응은 각각 다르다(방송 수와 함께 움직여야 / 지터가 안 듣는다 / 네트워크·적체).
 * close 1008 도 둘이다 — 제재와 탈퇴이고, 합치면 사용자가 스스로 누른 탈퇴가 제재율을 올린다.
 *
 * <p>⚠️ <b>사유의 합은 서버 주도 종료 전체가 아니다.</b> 한때 이 자리에 그렇게 적혀 있었는데 거짓이고,
 * 이 테스트도 그것을 재지 않는다 — 입장 거부 셋({@code KICKED}·{@code BANNED}·{@code ROOM_ENDED})은
 * 세션이 성립하기 전이라 이 미터에 오지 않고, 프레임 상한 초과(close 1009)는 전송 계층이 보낸다.
 * 여기서 재는 것은 <b>성립한 세션이 서버 판단으로 끊길 때 그 사유가 밖으로 나가는가</b> 하나다.
 */
@DisplayName("세션 종료 관측 — close 1000 세 갈래를 사유로 가른다")
class ChatSessionRegistryMetricsTest {

    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    private ChatSessionRepository sessionRepository;
    private MeterRegistry registry;
    private ChatSessionRegistry subject;

    private final UUID roomId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ChatSessionRepository.class);
        lenient().when(sessionRepository.add(any(), any(), any())).thenReturn(Mono.empty());
        lenient().when(sessionRepository.remove(any(), any())).thenReturn(Mono.empty());
        lenient().when(sessionRepository.count(any())).thenReturn(Mono.just(1L));
        registry = new SimpleMeterRegistry();
        subject = new ChatSessionRegistry(sessionRepository, new ChatPermissionPolicy(),
                new MicrometerChatMetrics(registry));
    }

    private String register() {
        String sessionId = "s-" + UUID.randomUUID();
        subject.register(sessionId, new ChatSession(roomId, UUID.randomUUID(), ChatRole.BUYER,
                "닉", "e@example.com", false)).block(VERIFY_CEILING);
        return sessionId;
    }

    private double cut(String reason) {
        Counter counter = registry.find("chat.session.cut").tags("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("⭐ 구현이 호출자의 사유를 그대로 싣는다 — 안 싣고 MODERATION 으로 고정해도 전 모듈이 초록이었다")
    void theReasonTheCallerPassesIsTheReasonReported() {
        // given: 사유를 가르는 축은 호출자에게 있다 — 핸들러 테스트는 **호출자가 넘기는 것**을 재고
        // (ChatSessionManager 가 목이다) 구현이 그것을 **쓰는지**는 아무도 재지 않았다.
        // 실측: closeUserEverywhere 가 사유를 무시하고 MODERATION 으로 고정해도 streaming-app
        // 29클래스 295칸이 전부 초록이고, WITHDRAWN 이 이 앱(main+test) 전체에 0건이었다.
        UUID userId = UUID.randomUUID();
        subject.register("s-withdrawn", new ChatSession(roomId, userId, ChatRole.BUYER,
                "닉", "e@example.com", false)).block(VERIFY_CEILING);

        // when
        subject.closeUserEverywhere(userId, SessionCutReason.WITHDRAWN).block(VERIFY_CEILING);

        // then: 탈퇴는 제재가 아니다 — 합치면 사용자가 스스로 누른 것이 제재율을 올린다
        assertThat(cut("withdrawn")).isEqualTo(1);
        assertThat(cut("moderation"))
                .as("탈퇴가 제재로 세어졌다 — close 코드가 양쪽 1008 이라 와이어로는 가려낼 수 없다")
                .isZero();
    }

    @Test
    @DisplayName("⭐ 수명 상한 종료가 reason=lifetime 으로 나간다")
    void lifetimeExpiryIsReported() {
        // when
        subject.terminateLifetimeExpired(register());

        // then
        assertThat(cut("lifetime")).isEqualTo(1);
        assertThat(cut("liveness")).as("두 사유는 대응이 다르다 — 지터 점검 vs 네트워크 점검").isZero();
        assertThat(cut("room_ended")).isZero();
    }

    @Test
    @DisplayName("⭐ 생존 확인 실패 종료가 reason=liveness 로 나간다")
    void livenessFailureIsReported() {
        // when
        subject.terminateUnresponsive(register());

        // then
        assertThat(cut("liveness")).isEqualTo(1);
        assertThat(cut("lifetime")).isZero();
    }

    @Test
    @DisplayName("⭐ 전송 경로에서 확인한 방 종료가 reason=room_ended 로 나간다")
    void roomEndedOnTheSendPathIsReported() {
        // when
        subject.terminateRoomEnded(register());

        // then
        assertThat(cut("room_ended")).isEqualTo(1);
        assertThat(cut("lifetime")).isZero();
    }

    @Test
    @DisplayName("⭐ 방 종료 일괄 종료가 세션마다 세어진다 — 방송 수와 함께 움직여야 하는 값이다")
    void closingAWholeRoomCountsEverySession() {
        // given: 같은 방에 세 세션
        register();
        register();
        register();

        // when
        subject.closeAll(roomId).block(VERIFY_CEILING);

        // then
        assertThat(cut("room_ended")).isEqualTo(3);
    }

    @Test
    @DisplayName("세션이 없으면 아무것도 세지 않는다 — 없는 세션을 끊었다고 지표가 오르면 안 된다")
    void anUnknownSessionReportsNothing() {
        // when
        subject.terminateLifetimeExpired("없는세션");
        subject.terminateUnresponsive("없는세션");
        subject.terminateRoomEnded("없는세션");

        // then
        assertThat(registry.find("chat.session.cut").counters()).isEmpty();
    }
}
