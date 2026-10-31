package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.chat.command.GetChatHistoryCommand;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatHistoryRepository;
import com.sapari.chat.domain.repository.ChatKickLogRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.view.GetChatHistoryView;
import com.sapari.live.port.GetLiveRoomUseCase;
import com.sapari.live.view.LiveRoomView;

/**
 * 이력 조회 서비스.
 *
 * <p>세 축을 본다 — <b>특권 뷰 판정</b>(누가 원문·이메일을 보는가), <b>강퇴자 제외가 질의로 간다</b>는 것,
 * 그리고 <b>재생 위치 → 시각 구간</b> 변환.
 *
 * <p>특권 판정이 이 테스트의 중심이다. 코드에 경고가 적혀 있었다 — 소유만 보면 <b>실시간은 관리자에게
 * 열리고 이력은 닫히는</b> 두 갈래 정책이 된다. 실시간 팬아웃과 같은 판정을 쓰는지를 여기서 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GetChatHistoryService — 특권 뷰·강퇴자 제외·VOD 창")
class GetChatHistoryServiceTest {

    private static final Instant STARTED_AT = Instant.parse("2026-06-18T10:00:00Z");

    @Mock
    private GetLiveRoomUseCase liveRooms;

    @Mock
    private ChatHistoryRepository historyRepository;

    @Mock
    private ChatKickLogRepository kickLogRepository;

    // 순수 정책은 가짜로 바꾸지 않는다 — 목으로 두면 이 테스트가 재려는 판정이 사라진다.
    private GetChatHistoryService service;

    private final UUID roomId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();
    private final UUID buyerId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID kickedId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new GetChatHistoryService(liveRooms, historyRepository, kickLogRepository,
                new ChatPermissionPolicy());
        lenient().when(liveRooms.findRoom(roomId))
                .thenReturn(Optional.of(new LiveRoomView(roomId, sellerId, false, STARTED_AT)));
        lenient().when(kickLogRepository.findKickedUserIds(roomId)).thenReturn(List.of());
    }

    private GetChatHistoryCommand cursorBy(UUID requesterId, String role) {
        return new GetChatHistoryCommand(roomId, requesterId, role, null, null, 20);
    }

    private ChatMessage message() {
        return ChatMessage.builder()
                .id("68000000000000000000000a")
                .roomId(roomId)
                .senderId(buyerId)
                .senderNickname("닉")
                .senderEmail("buyer@example.com")
                .senderRole(ChatRole.BUYER)
                .type(new ChatMessageType.Normal())
                .originalMessage("시발")
                .displayMessage("시*")
                .clientMsgId("c-1")
                .createdAt(STARTED_AT.plusSeconds(60))
                .build();
    }

    private void stubOnePage() {
        given(historyRepository.findBefore(eq(roomId), any(), anyInt(), any()))
                .willReturn(List.of(message()));
    }

    @Test
    @DisplayName("⭐ 방 주인에게는 원문·발신자 이메일이 채워진다")
    void theRoomOwnerSeesTheOriginalAndTheEmail() {
        // given
        stubOnePage();

        // when
        GetChatHistoryView view = service.getHistory(cursorBy(sellerId, "SELLER"));

        // then
        assertThat(view.messages()).singleElement().satisfies(message -> {
            assertThat(message.originalMessage()).isEqualTo("시발");
            assertThat(message.senderEmail()).isEqualTo("buyer@example.com");
            assertThat(message.displayMessage()).isEqualTo("시*");
        });
    }

    @Test
    @DisplayName("⭐ 관리자도 같은 것을 본다 — 실시간이 관리자에게 열려 있으므로 이력이 닫히면 두 갈래가 된다")
    void anAdminSeesTheSameAsTheOwner() {
        // given
        stubOnePage();

        // when
        GetChatHistoryView view = service.getHistory(cursorBy(adminId, "ADMIN"));

        // then
        assertThat(view.messages()).singleElement().satisfies(message -> {
            assertThat(message.originalMessage()).isEqualTo("시발");
            assertThat(message.senderEmail()).isEqualTo("buyer@example.com");
        });
    }

    @Test
    @DisplayName("⭐ 남의 방 판매자는 못 본다 — 역할이 아니라 소유가 축이다")
    void aSellerOfAnotherRoomSeesNothingPrivileged() {
        // given
        stubOnePage();

        // when
        GetChatHistoryView view = service.getHistory(cursorBy(UUID.randomUUID(), "SELLER"));

        // then
        assertThat(view.messages()).singleElement().satisfies(message -> {
            assertThat(message.originalMessage()).isNull();
            assertThat(message.senderEmail()).isNull();
        });
    }

    @Test
    @DisplayName("일반 구매자와 비회원은 마스킹만 본다")
    void buyersAndAnonymousRequestersSeeOnlyMaskedContent() {
        // given
        stubOnePage();

        // when & then
        assertThat(service.getHistory(cursorBy(buyerId, "BUYER")).messages())
                .singleElement().satisfies(message -> {
                    assertThat(message.originalMessage()).isNull();
                    assertThat(message.senderEmail()).isNull();
                });
        assertThat(service.getHistory(cursorBy(null, null)).messages())
                .singleElement().satisfies(message -> {
                    assertThat(message.originalMessage()).isNull();
                    assertThat(message.senderEmail()).isNull();
                });
    }

    @Test
    @DisplayName("⭐ 강퇴자 목록이 질의로 내려간다 — 서비스가 뒤에서 걸러내면 스크롤이 조용히 끝난다")
    void theKickedSetIsPushedIntoTheQuery() {
        // given
        given(kickLogRepository.findKickedUserIds(roomId)).willReturn(List.of(kickedId));
        stubOnePage();

        // when
        service.getHistory(cursorBy(buyerId, "BUYER"));

        // then
        ArgumentCaptor<java.util.Collection<UUID>> captor = ArgumentCaptor.captor();
        then(historyRepository).should().findBefore(eq(roomId), any(), eq(20), captor.capture());
        assertThat(captor.getValue()).containsExactly(kickedId);
    }

    @Test
    @DisplayName("⭐ VOD 창은 시작 시각 + 재생 위치에서 5분 뒤로 — 경과 시간을 저장하지 않으므로 여기서 역산한다")
    void thePlaybackWindowIsDerivedFromTheStartTime() {
        // given: 영상 10분 지점
        given(historyRepository.findWindow(eq(roomId), any(), any(), anyInt(), any()))
                .willReturn(List.of(message()));

        // when
        service.getHistory(new GetChatHistoryCommand(roomId, buyerId, "BUYER", null, 600_000L, 20));

        // then: [10분 − 5분, 10분] 구간, 상한 200
        then(historyRepository).should().findWindow(eq(roomId),
                eq(STARTED_AT.plusSeconds(300)), eq(STARTED_AT.plusSeconds(600)), eq(200), any());
        then(historyRepository).should(never()).findBefore(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("⭐ 재생 위치가 창보다 이르면 시작 시각에서 끊는다 — 구간이 방송 앞으로 가지 않게")
    void anEarlyPositionClampsToTheStartTime() {
        // given: 영상 1분 지점 — 5분 창이 시작 앞으로 간다
        given(historyRepository.findWindow(eq(roomId), any(), any(), anyInt(), any()))
                .willReturn(List.of());

        // when
        service.getHistory(new GetChatHistoryCommand(roomId, buyerId, "BUYER", null, 60_000L, 20));

        // then
        then(historyRepository).should().findWindow(eq(roomId),
                eq(STARTED_AT), eq(STARTED_AT.plusSeconds(60)), eq(200), any());
    }

    @Test
    @DisplayName("⭐ VOD 모드는 size 를 무시한다 — 클라가 상한을 조절하지 못한다")
    void thePlaybackModeIgnoresSize() {
        // given
        given(historyRepository.findWindow(eq(roomId), any(), any(), anyInt(), any()))
                .willReturn(List.of());

        // when: size=100 을 보내도
        service.getHistory(new GetChatHistoryCommand(roomId, buyerId, "BUYER", null, 600_000L, 100));

        // then: 200 고정
        then(historyRepository).should().findWindow(any(), any(), any(), eq(200), any());
    }

    @Test
    @DisplayName("없는 방은 빈 목록이 아니라 거부다 — 빈 목록은 \"그 방은 있는데 조용했다\"와 구분되지 않는다")
    void anUnknownRoomIsRejected() {
        // given
        given(liveRooms.findRoom(roomId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.getHistory(cursorBy(buyerId, "BUYER")))
                .isInstanceOf(ChatPermissionDeniedException.class);
    }

    @Test
    @DisplayName("시작하지 않은 방의 VOD 조회는 거부다 — 매핑할 기준이 없다")
    void aRoomThatNeverStartedHasNoPlaybackHistory() {
        // given
        given(liveRooms.findRoom(roomId))
                .willReturn(Optional.of(new LiveRoomView(roomId, sellerId, false, null)));

        // when & then
        assertThatThrownBy(() -> service.getHistory(
                new GetChatHistoryCommand(roomId, buyerId, "BUYER", null, 600_000L, 20)))
                .isInstanceOf(ChatPermissionDeniedException.class);
    }

    @Test
    @DisplayName("⭐ 모르는 역할은 조용히 좁히지 않고 던진다 — 정당한 운영자가 원문을 못 보는 상태가 버그로 안 보인다")
    void anUnknownRoleIsNotSilentlyNarrowed() {
        assertThatThrownBy(() -> service.getHistory(cursorBy(sellerId, "SUPERUSER")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPERUSER");
    }
}
