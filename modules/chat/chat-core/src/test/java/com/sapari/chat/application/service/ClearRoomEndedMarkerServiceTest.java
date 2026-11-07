package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import com.sapari.chat.command.ClearRoomEndedMarkerCommand;
import com.sapari.chat.domain.model.ChatAuditAction;
import com.sapari.chat.domain.model.ChatAuditEntry;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.exception.LiveNotActiveException;
import com.sapari.chat.domain.repository.ChatRoomEndedWriteRepository;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.port.GetLiveRoomUseCase;
import com.sapari.live.view.LiveRoomView;

/**
 * 종료 마커 해제 — <b>진위 검증이 이 서비스의 내용</b>이라는 것을 잰다.
 *
 * <p>정본 L20 의 두 결함(진위 검증 없음 · 해제 경로 없음)이 하나로 묶여 있고, 그래서 해제 경로가
 * 곧 그 검증이다. chat 은 받은 Pub/Sub 신호의 진위를 확인할 수단이 없지만 live 의 정본을 읽는
 * 앱은 있다 — 그 방이 <b>실제로</b> 진행 중이면 받은 신호가 거짓이었다는 뜻이고, 끝난 방이면
 * 마커가 맞으므로 거부해야 한다.
 *
 * <p><b>LIVE 검증이 권한을 넓힐 수 있게 해 준다.</b> 그것이 없으면 이 엔드포인트는 "끝난 방을
 * 다시 열 수 있는 엔드포인트" 가 되고, 그러면 판매자에게 열 수 없다. 그래서 여기서 가장 세게
 * 박는 단언이 "진행 중이 아니면 거부" 다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ClearRoomEndedMarkerService — 진행 중인 방만 풀 수 있다")
class ClearRoomEndedMarkerServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T02:00:00Z");

    @Mock
    private GetLiveRoomUseCase liveRoomReader;
    @Mock
    private ChatRoomEndedWriteRepository roomEndedWriteRepository;
    @Mock
    private ChatAuditRecorder auditRecorder;

    private ClearRoomEndedMarkerService service;

    private final UUID roomId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ClearRoomEndedMarkerService(liveRoomReader, roomEndedWriteRepository,
                auditRecorder, new ChatPermissionPolicy(),
                new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private ClearRoomEndedMarkerCommand command(UUID actorId, String role) {
        return new ClearRoomEndedMarkerCommand(roomId, actorId, role);
    }

    /**
     * ⚠️ {@code live=true} 는 <b>심는 값</b>이고, 생산 경로가 그것을 만들 수 있다는 증거가 아니다.
     *
     * <p>사슬을 전수로 재면 마커가 존재하는 모든 순간 그 방은 DB 에서 이미 {@code Ended} 다 —
     * {@code markEnded} 는 {@code live:room:ended} 구독에서만 불리고, 발행하는 둘
     * ({@code EndLiveService}·{@code EndStaleLiveService})은 {@code endLive() → save() →
     * afterCommit() → publish} 순서다. 그래서 "진행 중인데 마커가 있는" 상태는 <b>앱 경로로
     * 만들어지지 않는다</b>.
     *
     * <p>그러니 아래 {@code clearsTheMarkerWhenTheRoomIsActuallyLive} 가 재는 것은 "앱 밖에서
     * 들어온 발행을 지울 수 있다" 이고, 그 이상으로 읽지 말 것. 이 메서드가 그 사실을 들고 있는
     * 이유는, 심은 값을 보고 "배치 오판도 닫힌다" 로 읽는 것이 정확히 내가 저질렀던 오독이기
     * 때문이다({@code ClearRoomEndedMarkerService} javadoc 이 그 정정을 싣는다).
     */
    private void roomIs(boolean live) {
        given(liveRoomReader.findRoom(roomId))
                .willReturn(Optional.of(new LiveRoomView(roomId, sellerId, live, NOW)));
    }

    @Test
    @DisplayName("⭐ 진행 중인 방의 마커는 해제된다 — 앱 밖에서 들어온 발행이었다는 뜻이다")
    void clearsTheMarkerWhenTheRoomIsActuallyLive() {
        // given
        roomIs(true);

        // when
        service.clear(command(sellerId, "SELLER"));

        // then
        verify(roomEndedWriteRepository).clearEnded(roomId);
    }

    /**
     * ⭐ 해제가 성공하면 감사 한 줄이 남는다.
     *
     * <p>{@code detail} 이 비어 있는 것이 이 행위의 모양이다 — 방은 {@code targetId} 가 가리키므로
     * 더 실을 것이 없다. 비는 경로가 실제로 있다는 사실이 어댑터에서 따로 측정된다
     * ({@code ChatAuditLogRepositoryImplTest}: {@code CAST(NULL AS jsonb)} 가 통하는가).
     */
    @Test
    @DisplayName("⭐ 해제가 성공하면 감사 한 줄이 남는다 — detail 은 비어 있다")
    void aSuccessfulClearIsAudited() {
        // given
        roomIs(true);

        // when
        service.clear(command(sellerId, "SELLER"));

        // then
        ArgumentCaptor<ChatAuditEntry> captor = ArgumentCaptor.forClass(ChatAuditEntry.class);
        verify(auditRecorder).record(captor.capture());
        ChatAuditEntry entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(ChatAuditAction.CHAT_CLEAR_ROOM_ENDED_MARKER);
        assertThat(entry.actorId()).isEqualTo(sellerId);
        assertThat(entry.actorRole()).isEqualTo(ChatRole.SELLER);
        assertThat(entry.targetId()).isEqualTo(roomId);
        assertThat(entry.detail()).isNull();
        assertThat(entry.occurredAt()).isEqualTo(NOW);
    }

    /**
     * ⭐ <b>되돌려진 조치는 감사에 남지 않는다.</b>
     *
     * <p>지운 뒤 다시 물어 방이 끝나 있으면 마커를 되세우고 거부한다 — 그 요청은 해제가 아니었다.
     * 감사 쓰기를 삭제 직후에 두면 이 경로에서 "수행됨" 이 남고, 일어나지 않은 조치가 기록에 남는
     * 쪽이 비는 쪽보다 나쁜 거짓이다({@code ChatAuditEntry} javadoc).
     *
     * <p>이 경로 자체도 여기서 처음 측정된다 — {@code markEndedAgain} 을 거는 테스트가 없었다.
     */
    @Test
    @DisplayName("⭐ 지우는 사이에 방이 끝나면 마커를 되세우고 감사도 남기지 않는다")
    void aRevertedClearIsNotAudited() {
        // given: 첫 읽기는 진행 중, 지운 뒤 다시 읽을 때는 끝나 있다
        given(liveRoomReader.findRoom(roomId))
                .willReturn(Optional.of(new LiveRoomView(roomId, sellerId, true, NOW)))
                .willReturn(Optional.of(new LiveRoomView(roomId, sellerId, false, NOW)));

        // when & then
        assertThatThrownBy(() -> service.clear(command(sellerId, "SELLER")))
                .isInstanceOf(LiveNotActiveException.class)
                .hasMessageContaining("되돌렸다");

        verify(roomEndedWriteRepository).clearEnded(roomId);
        verify(roomEndedWriteRepository).markEndedAgain(roomId);
        verify(auditRecorder, never()).record(any());
    }

    /** 거부된 요청은 감사에 남지 않는다 — 조치가 수행되지 않았다. */
    @Test
    @DisplayName("끝난 방 거부는 감사에 남지 않는다")
    void aRefusalIsNotAudited() {
        // given
        roomIs(false);

        // when & then
        assertThatThrownBy(() -> service.clear(command(sellerId, "SELLER")))
                .isInstanceOf(LiveNotActiveException.class);

        verify(auditRecorder, never()).record(any());
    }

    /**
     * ⭐ 이 단언이 이 엔드포인트를 안전하게 만든다.
     *
     * <p>빠지면 "종료된 방을 다시 열 수 있는 엔드포인트" 가 되고, 그러면 판매자에게 열 수 없다 —
     * 자기 방송이 끝난 뒤에도 채팅을 되살릴 수 있게 되기 때문이다. 권한을 넓힌 근거가 이 검증이라,
     * 둘 중 하나만 바꾸면 다른 하나가 거짓이 된다.
     */
    @Test
    @DisplayName("⭐ 끝난 방은 거부한다 — 그 마커는 맞는 마커다")
    void refusesWhenTheRoomHasReallyEnded() {
        // given
        roomIs(false);

        // when & then
        assertThatThrownBy(() -> service.clear(command(sellerId, "SELLER")))
                .isInstanceOf(LiveNotActiveException.class);

        verify(roomEndedWriteRepository, never()).clearEnded(any());
    }

    @Test
    @DisplayName("관리자는 남의 방도 풀 수 있다 — 방 주인이 못 알아채는 경우가 있다")
    void adminMayClearSomeoneElsesRoom() {
        // given
        roomIs(true);

        // when & then
        assertThatCode(() -> service.clear(command(UUID.randomUUID(), "ADMIN")))
                .doesNotThrowAnyException();
        verify(roomEndedWriteRepository).clearEnded(roomId);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SELLER", "USER"})
    @DisplayName("⭐ 남의 방은 방 주인도 구매자도 풀 수 없다 — 역할이 아니라 소유가 가른다")
    void othersMayNotClearARoomTheyDoNotOwn(String role) {
        // given: 진행 중이지만 내 방이 아니다
        roomIs(true);

        // when & then
        assertThatThrownBy(() -> service.clear(command(UUID.randomUUID(), role)))
                .isInstanceOf(ChatPermissionDeniedException.class);

        verify(roomEndedWriteRepository, never()).clearEnded(any());
    }

    @Test
    @DisplayName("없는 방은 권한 거부로 접는다 — '없음' 과 '권한 없음' 을 가르면 방 열거 오라클이 된다")
    void anUnknownRoomIsFoldedIntoPermissionDenied() {
        // given
        given(liveRoomReader.findRoom(roomId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.clear(command(sellerId, "ADMIN")))
                .isInstanceOf(ChatPermissionDeniedException.class);
    }

    /**
     * ⚠️ 이 테스트는 한때 <b>인자를 받지 않았다.</b> {@code @ValueSource} 는 셋인데 메서드 시그니처가
     * 비어 있고 본문이 {@code "OPERATOR"} 를 하드코딩해서, 같은 값으로 세 번 돌고 {@code "seller"}·
     * {@code ""} 는 <b>한 번도 실행되지 않았다</b> — 조용히 초록이었다. 바로 아래
     * {@code ReconcileKickListServiceTest} 에서 복사하다 인자가 떨어진 것이다.
     *
     * <p>⚠️ 전에 여기에 {@code ""} 가 있었고 "커맨드에 컴팩트 생성자가 없어 서비스까지 도달하는
     * 유일한 공란 벡터" 라고 적혀 있었다. <b>그 문장이 거짓이 됐다</b> — 그 생성자를 채웠으므로
     * 공란은 이제 커맨드 경계에서 끊긴다. 아래 경계 칸이 그 자리를 받는다.
     */
    @ParameterizedTest(name = "role={0}")
    @ValueSource(strings = {"OPERATOR", "seller"})
    @DisplayName("모르는 역할 이름은 방을 읽기도 전에 거부한다")
    void unknownRoleIsRejectedBeforeReadingTheRoom(String role) {
        // when & then
        assertThatThrownBy(() -> service.clear(command(sellerId, role)))
                .isInstanceOf(ChatPermissionDeniedException.class)
                .hasMessageContaining("알 수 없는 역할");

        verify(liveRoomReader, never()).findRoom(any());
    }

    /**
     * 빈 값·{@code null} 은 <b>커맨드 경계</b>에서 끊긴다 — 서비스까지 오지 않는다. 형제
     * {@code ReleaseBanServiceTest} 와 같은 모양이고, 여기서는 <b>어디서 끊기는지</b>를 그대로
     * 적는다("거부된다" 만 재면 다음 사람이 그 생성자를 지워도 서비스가 받아 주는 바람에 초록으로
     * 남는다).
     *
     * <p>이 경로에서 그 경계가 한 칸 더 무겁다 — 감사 기록이 {@code actorRole} 을 싣는데 이 경로는
     * DB 트랜잭션이 없어 그 기록을 <b>조치 뒤에</b> 만든다. 커맨드가 비어 있으면 거기서야 터지고,
     * 그때는 조치가 이미 수행된 뒤다.
     */
    @ParameterizedTest(name = "role=[{0}]")
    @ValueSource(strings = {"", "   "})
    @DisplayName("빈 역할은 커맨드 생성에서 거부된다 — 서비스까지 오지 않는다")
    void blankRoleIsRejectedAtTheCommandBoundary(String role) {
        assertThatThrownBy(() -> command(sellerId, role))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actorRole");
    }

    @Test
    @DisplayName("역할이 null 이어도 커맨드 생성에서 거부된다")
    void nullRoleIsRejectedAtTheCommandBoundary() {
        assertThatThrownBy(() -> command(sellerId, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * ⭐ <b>감사 쓰기가 실패해도 해제 로그는 남는다</b> — 형제
     * {@code ReconcileKickListServiceTest} 의 같은 칸과 같은 이유다(리뷰 지적 ㄱ-2). 감사 호출이
     * 로그보다 앞이면 그 실패 분기에서 "조치는 수행됐고 감사도 로그도 없다" 가 된다.
     */
    @Test
    @DisplayName("⭐ 감사 쓰기가 실패해도 해제 로그는 남는다 — 폴백이 그 로그다")
    void theActionIsStillLoggedWhenTheAuditWriteFails() {
        // given
        roomIs(true);
        org.mockito.BDDMockito.willThrow(new IllegalStateException("감사 INSERT 실패"))
                .given(auditRecorder).record(any());

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(ClearRoomEndedMarkerService.class);
        logger.addAppender(appender);
        try {
            // when
            assertThatThrownBy(() -> service.clear(command(sellerId, "SELLER")))
                    .isInstanceOf(IllegalStateException.class);

            // then
            assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                    .as("감사도 로그도 없다 — 수행된 조치의 흔적이 어디에도 남지 않았다")
                    .anySatisfy(message -> assertThat(message).contains("방 종료 마커 해제"));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
