package com.sapari.streamingapp.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatSessionRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;

import reactor.core.publisher.Mono;

/**
 * 팬아웃 1회의 <b>직렬화 횟수</b>를 고정한다 — 세션 수가 아니라 <b>종류 수</b>여야 한다.
 *
 * <p>직렬화는 세션별 아웃바운드 파이프라인에서 일어나므로, 공유 장치가 없으면 방에 N 세션이면
 * 같은 문자열을 N 번 만든다. 반면 서로 다른 payload 는 몇 종뿐이다
 * ({@code fanOutChat} 은 특권 × 발신자로 4종). <b>실측</b>: 직렬화 1회 0.77us →
 * 12,000 세션 한 메시지에 <b>9.23ms</b>, 4종이면 0.0031ms. 약 3,000 배이고 그 비용이
 * 이벤트루프 경로에서 난다.
 *
 * <p>그래서 이 파일이 재는 것은 "메시지가 도착했는가" 가 아니라 <b>"몇 번 만들었는가"</b> 다.
 * 전달은 다른 테스트들이 이미 덮고 있고, 여기 단언이 없으면 공유 장치를 떼도 전부 초록이다.
 */
@DisplayName("아웃바운드 프레임 공유 — 직렬화가 종류 수에 비례한다")
class OutboundFrameSharingTest {

    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    private static final int SESSIONS = 10;

    private ChatSessionRegistry registry;
    private final UUID roomId = UUID.randomUUID();

    /** 핸들러가 하는 직렬화를 흉내 내면서 횟수를 센다. */
    private final AtomicInteger serializations = new AtomicInteger();
    private final Function<OutboundMessage, String> counting = message -> {
        serializations.incrementAndGet();
        return message.type() + ":" + message.activeCount();
    };

    @BeforeEach
    void setUp() {
        ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
        lenient().when(sessionRepository.add(any(), any(), any())).thenReturn(Mono.empty());
        lenient().when(sessionRepository.heartbeatInterval()).thenReturn(Duration.ofMinutes(10));
        registry = new ChatSessionRegistry(sessionRepository, new ChatPermissionPolicy(), ChatMetrics.NOOP);
    }

    /** 세션 10개를 등록하고 각자의 프레임 흐름을 모은다 — 절반은 방 주인이라 뷰가 둘로 갈린다. */
    private List<OutboundFrame> registerAndCollect() {
        List<OutboundFrame> frames = new CopyOnWriteArrayList<>();
        for (int i = 0; i < SESSIONS; i++) {
            boolean owner = i % 2 == 0;
            String sid = "s" + i;
            registry.register(sid, new ChatSession(roomId, UUID.randomUUID(),
                            owner ? ChatRole.SELLER : ChatRole.BUYER, "닉", "e@example.com", owner))
                    .block(VERIFY_CEILING);
            registry.outboundFrames(sid).subscribe(frames::add);
        }
        return frames;
    }

    @Test
    @DisplayName("⭐ 뷰 둘을 10 세션에 뿌려도 직렬화는 2회다 — 공유가 없으면 10회다")
    void aFanOutSerializesOncePerDistinctView() {
        // given: 호출부가 뷰를 미리 만들어 재사용하는 모양(fanOutChat 과 같다)
        List<OutboundFrame> frames = registerAndCollect();
        OutboundMessage ownerView = OutboundMessage.roomInfo(7L, true);
        OutboundMessage viewerView = OutboundMessage.roomInfo(7L, false);

        // when
        registry.sendToRoomGated(roomId,
                session -> session.isRoomOwner() ? ownerView : viewerView).block(VERIFY_CEILING);
        // 핸들러가 세션마다 하는 일을 그대로 한다
        frames.forEach(frame -> frame.utf8(counting));

        // then
        assertThat(frames).as("열 세션이 각자 한 프레임을 받아야 한다").hasSize(SESSIONS);
        assertThat(serializations)
                .as("직렬화가 세션 수에 비례한다 — 같은 뷰를 받는 세션들이 프레임을 공유하지 않았다")
                .hasValue(2);

        // ⭐ 같은 뷰를 받는 세션들은 <b>같은 배열 인스턴스</b>를 받는다. 그게 이 변경의 하중 지점이다 —
        // 세션은 그 배열을 자기 버퍼로 감싸기만 하므로 UTF-8 인코딩도 종류 수만큼만 돈다.
        // 인스턴스 동일성으로 재는 이유: 내용 비교는 "따로 만들어 같은 값" 과 구분되지 않는다.
        byte[] ownerBytes = frames.get(0).utf8(counting);
        byte[] viewerBytes = frames.get(1).utf8(counting);
        assertThat(frames.get(2).utf8(counting)).as("방 주인 세션들이 배열을 공유하지 않았다")
                .isSameAs(ownerBytes);
        assertThat(frames.get(3).utf8(counting)).as("일반 세션들이 배열을 공유하지 않았다")
                .isSameAs(viewerBytes);
        assertThat(ownerBytes).as("뷰가 다르면 바이트도 달라야 한다").isNotSameAs(viewerBytes);
        assertThat(serializations).as("이미 만들어 둔 것을 다시 만들었다").hasValue(2);
    }

    @Test
    @DisplayName("⭐ 전원이 같은 메시지면 직렬화는 1회다 — sendToRoomLocal 도 프레임을 공유한다")
    void aBroadcastToEveryoneSerializesOnce() {
        List<OutboundFrame> frames = registerAndCollect();

        registry.sendToRoomLocal(roomId, OutboundMessage.roomInfo(3L, false)).block(VERIFY_CEILING);
        frames.forEach(frame -> frame.utf8(counting));

        assertThat(frames).hasSize(SESSIONS);
        assertThat(serializations).hasValue(1);
    }

    @Test
    @DisplayName("같은 프레임을 두 번 읽어도 한 번만 만든다 — 지연 계산이 멱등이다")
    void readingTheSameFrameTwiceSerializesOnce() {
        OutboundFrame frame = new OutboundFrame(OutboundMessage.roomInfo(1L, false));

        byte[] first = frame.utf8(counting);
        byte[] second = frame.utf8(counting);

        assertThat(second).isSameAs(first);
        assertThat(serializations).hasValue(1);
    }

    @Test
    @DisplayName("아무도 소비하지 않은 프레임은 직렬화되지 않는다 — 끊긴 세션의 비용을 내지 않는다")
    void anUnconsumedFrameIsNeverSerialized() {
        // 구독하지 않은 세션에게만 보낸다 — 프레임은 버퍼에 남고 wire 는 불리지 않는다
        registry.register("lonely", new ChatSession(roomId, UUID.randomUUID(),
                ChatRole.BUYER, "닉", "e@example.com", false)).block(VERIFY_CEILING);

        registry.sendToRoomLocal(roomId, OutboundMessage.roomInfo(1L, false)).block(VERIFY_CEILING);

        assertThat(serializations).hasValue(0);
    }
}
