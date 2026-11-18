package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.sapari.chat.command.GetChatNoticeCommand;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatHistoryRepository;
import com.sapari.chat.domain.repository.ChatKickLogRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.view.ChatMessageView;
import com.sapari.live.port.GetLiveRoomUseCase;
import com.sapari.live.view.LiveRoomView;

/**
 * 방의 현재 공지 — 이력 조회와 같은 판정(없는 방 거부 · 특권 뷰 · 강퇴자 제외)을 쓰는가.
 *
 * <p>판정은 {@link GetChatHistoryServiceTest} 가 이력 쪽에서 잰다. 여기는 공지 경로가 그 판정을
 * <b>실제로 거치는지</b>를 잰다 — 같은 클래스라도 메서드가 다르면 한쪽만 판정을 빠뜨릴 수 있다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GetChatHistoryService.findLatestNotice — 이력과 같은 판정으로 한 건")
class GetChatNoticeServiceTest {

    private static final Instant STARTED_AT = Instant.parse("2026-06-18T10:00:00Z");

    @Mock
    private GetLiveRoomUseCase liveRooms;

    @Mock
    private ChatHistoryRepository historyRepository;

    @Mock
    private ChatKickLogRepository kickLogRepository;

    private GetChatHistoryService service;

    private final UUID roomId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();
    private final UUID buyerId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID kickedId = UUID.randomUUID();

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        serviceLogger = (Logger) LoggerFactory.getLogger(GetChatHistoryService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
        service = new GetChatHistoryService(liveRooms, historyRepository, kickLogRepository,
                new ChatPermissionPolicy());
        lenient().when(liveRooms.findRoom(roomId))
                .thenReturn(Optional.of(new LiveRoomView(roomId, sellerId, true, STARTED_AT)));
        lenient().when(kickLogRepository.findKickedUserIds(roomId)).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
        appender.stop();
    }

    private GetChatNoticeCommand by(UUID requesterId, String role) {
        return new GetChatNoticeCommand(roomId, requesterId, role);
    }

    private ChatMessage notice() {
        return ChatMessage.builder()
                .id("68000000000000000000000b")
                .roomId(roomId)
                .senderId(sellerId)
                .senderNickname("판매자")
                // 저장소는 이메일을 돌려주지 못한다(영속하지 않는다 — ChatMessageDocument). 어댑터 모양 그대로.
                .senderEmail(null)
                .senderRole(ChatRole.SELLER)
                .type(new ChatMessageType.Notice())
                .originalMessage("시발 할인")
                .displayMessage("시* 할인")
                .clientMsgId("n-1")
                .createdAt(STARTED_AT.plusSeconds(60))
                .build();
    }

    private void stubNotice() {
        given(historyRepository.findLatestNotice(eq(roomId), any())).willReturn(Optional.of(notice()));
    }

    @Test
    @DisplayName("⭐ 방 주인에게는 원문이 채워지고, 이메일은 공지 조회에 없다")
    void theRoomOwnerSeesTheOriginal() {
        stubNotice();

        Optional<ChatMessageView> view = service.findLatestNotice(by(sellerId, "SELLER"));

        assertThat(view).hasValueSatisfying(notice -> {
            assertThat(notice.type()).isEqualTo("NOTICE");
            assertThat(notice.originalMessage()).isEqualTo("시발 할인");
            assertThat(notice.senderEmail()).isNull();
            assertThat(notice.displayMessage()).isEqualTo("시* 할인");
            assertThat(notice.createdAt()).isEqualTo(STARTED_AT.plusSeconds(60));
        });
    }

    @Test
    @DisplayName("⭐ 구매자·비회원은 마스킹된 본문만 받는다")
    void buyersAndAnonymousSeeOnlyMaskedContent() {
        stubNotice();

        assertThat(List.of(
                service.findLatestNotice(by(buyerId, "BUYER")),
                service.findLatestNotice(new GetChatNoticeCommand(roomId, null, null))))
                .allSatisfy(view -> assertThat(view).hasValueSatisfying(notice -> {
                    assertThat(notice.displayMessage()).isEqualTo("시* 할인");
                    assertThat(notice.originalMessage()).isNull();
                    assertThat(notice.senderEmail()).isNull();
                }));
    }

    @Test
    @DisplayName("⭐ 강퇴자 목록이 질의로 내려간다 — 이력에서 빠지는 공지가 여기서만 보이면 안 된다")
    void theKickedSetIsPushedIntoTheQuery() {
        given(kickLogRepository.findKickedUserIds(roomId)).willReturn(List.of(kickedId));
        stubNotice();

        service.findLatestNotice(by(buyerId, "BUYER"));

        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.captor();
        then(historyRepository).should().findLatestNotice(eq(roomId), captor.capture());
        assertThat(captor.getValue()).containsExactly(kickedId);
    }

    @Test
    @DisplayName("공지가 없으면 빈 값이다")
    void noNoticeIsEmpty() {
        given(historyRepository.findLatestNotice(eq(roomId), any())).willReturn(Optional.empty());

        assertThat(service.findLatestNotice(by(buyerId, "BUYER"))).isEmpty();
    }

    @Test
    @DisplayName("없는 방은 빈 값이 아니라 거부다 — 이력과 같은 답이어야 방 id 를 더듬는 통로가 하나다")
    void anUnknownRoomIsRejected() {
        given(liveRooms.findRoom(roomId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.findLatestNotice(by(buyerId, "BUYER")))
                .isInstanceOf(ChatPermissionDeniedException.class);
        then(historyRepository).should(never()).findLatestNotice(any(), any());
    }

    @Test
    @DisplayName("⭐ 남의 방 특권 뷰 공지 조회가 감사 로그로 남고, 이력 조회와 구분된다")
    void aPrivilegedNoticeReadInSomeoneElsesRoomIsAudited() {
        stubNotice();

        service.findLatestNotice(by(adminId, "ADMIN"));

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .filteredOn(line -> line.startsWith("특권 뷰"))
                .singleElement().asString()
                .startsWith("특권 뷰 공지 조회")
                .contains("ADMIN", adminId.toString(), roomId.toString())
                .doesNotContain("seller@example.com");
    }
}
