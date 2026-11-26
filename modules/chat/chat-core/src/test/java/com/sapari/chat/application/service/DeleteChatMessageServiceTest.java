package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.chat.application.port.ChatMessageDeletedPublisher;
import com.sapari.chat.command.DeleteChatMessageCommand;
import com.sapari.chat.domain.exception.ChatMessageNotFoundException;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.model.ChatAuditAction;
import com.sapari.chat.domain.model.ChatAuditEntry;
import com.sapari.chat.domain.model.ChatMessageDeletionTarget;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatMessageDeletionRepository;
import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.port.GetLiveRoomUseCase;
import com.sapari.live.view.LiveRoomView;

/**
 * 메시지 삭제가 <b>어디서 멈추는가</b>와 <b>무엇을 몇 번 남기는가</b>를 고정한다. 정책과 시계는 진짜, 포트만 목이다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeleteChatMessageService — 거부 단계와 한 번만 남는 감사")
class DeleteChatMessageServiceTest {

    @Mock
    private GetLiveRoomUseCase liveRoomReader;
    @Mock
    private ChatMessageDeletionRepository deletionRepository;
    @Mock
    private ChatMessageDeletedPublisher deletedPublisher;
    @Mock
    private ChatAuditRecorder auditRecorder;

    private DeleteChatMessageService service;

    private static final Instant NOW = Instant.parse("2026-11-26T00:00:00Z");

    private final UUID roomId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final String messageId = "68b7f0c2e1a4b93d5c0a1234";

    @BeforeEach
    void setUp() {
        service = new DeleteChatMessageService(liveRoomReader, deletionRepository, deletedPublisher,
                auditRecorder, new ChatPermissionPolicy(), new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private void roomIsLive(boolean live) {
        given(liveRoomReader.findRoom(roomId)).willReturn(Optional.of(new LiveRoomView(roomId, sellerId, live, NOW)));
    }

    private DeleteChatMessageCommand by(UUID actorId, String platformRole) {
        return new DeleteChatMessageCommand(roomId, actorId, platformRole, messageId);
    }

    private void messageBy(ChatRole senderRole, boolean deleted) {
        given(deletionRepository.findInRoom(roomId, messageId))
                .willReturn(Optional.of(new ChatMessageDeletionTarget(senderId, senderRole, deleted)));
    }

    @Test
    @DisplayName("⭐ 방 주인이 지우면 표시 → 감사 → 발행 순서로 한 번씩 남는다")
    void theOwnerDeletesAMessage() {
        // given
        roomIsLive(true);
        messageBy(ChatRole.BUYER, false);
        given(deletionRepository.markDeleted(roomId, messageId, sellerId, NOW)).willReturn(true);

        // when
        service.delete(by(sellerId, "SELLER"));

        // then
        InOrder order = inOrder(deletionRepository, deletedPublisher, auditRecorder);
        order.verify(deletionRepository).markDeleted(roomId, messageId, sellerId, NOW);
        ArgumentCaptor<ChatAuditEntry> audit = ArgumentCaptor.forClass(ChatAuditEntry.class);
        order.verify(auditRecorder).record(audit.capture());
        order.verify(deletedPublisher).publishDeleted(roomId, messageId);
        assertThat(audit.getValue()).isEqualTo(new ChatAuditEntry(sellerId, ChatRole.SELLER,
                ChatAuditAction.CHAT_DELETE_MESSAGE, roomId,
                Map.of("messageId", messageId, "senderId", senderId.toString()), NOW));
    }

    @Test
    @DisplayName("⭐ 끝난 방에서도 지울 수 있다 — 다시보기도 같은 이력을 읽는다")
    void anEndedRoomStillAllowsDeletion() {
        roomIsLive(false);
        messageBy(ChatRole.BUYER, false);
        given(deletionRepository.markDeleted(roomId, messageId, sellerId, NOW)).willReturn(true);

        service.delete(by(sellerId, "SELLER"));

        verify(deletedPublisher).publishDeleted(roomId, messageId);
    }

    @Test
    @DisplayName("ADMIN 은 남의 방에서도 지운다")
    void anAdminDeletesInAnyRoom() {
        UUID adminId = UUID.randomUUID();
        roomIsLive(true);
        messageBy(ChatRole.SELLER, false);
        given(deletionRepository.markDeleted(roomId, messageId, adminId, NOW)).willReturn(true);

        service.delete(by(adminId, "ADMIN"));

        verify(auditRecorder).record(any());
    }

    @Test
    @DisplayName("⭐ 이미 지운 메시지는 성공이다 — 다시 알리지만 표시도 감사도 다시 남기지 않는다")
    void reDeletingIsASuccessfulNoOp() {
        roomIsLive(true);
        messageBy(ChatRole.BUYER, true);

        service.delete(by(sellerId, "SELLER"));

        verify(deletionRepository, never()).markDeleted(any(), anyString(), any(), any());
        verify(deletedPublisher).publishDeleted(roomId, messageId);
        verifyNoInteractions(auditRecorder);
    }

    @Test
    @DisplayName("⭐ 겹친 요청에서 표시를 못 단 쪽은 감사 행을 남기지 않는다")
    void theLoserOfARaceLeavesNoAuditRow() {
        roomIsLive(true);
        messageBy(ChatRole.BUYER, false);
        given(deletionRepository.markDeleted(roomId, messageId, sellerId, NOW)).willReturn(false);

        service.delete(by(sellerId, "SELLER"));

        verify(deletedPublisher).publishDeleted(roomId, messageId);
        verifyNoInteractions(auditRecorder);
    }

    @Test
    @DisplayName("⭐ 방 주인도 관리자도 아니면 메시지를 읽기 전에 403 — 응답 차이로 id 를 훑지 못한다")
    void aNonModeratorIsStoppedBeforeTheLookup() {
        roomIsLive(true);

        assertThatThrownBy(() -> service.delete(by(UUID.randomUUID(), "SELLER")))
                .isInstanceOf(ChatPermissionDeniedException.class);
        assertThatThrownBy(() -> service.delete(by(senderId, "USER")))
                .isInstanceOf(ChatPermissionDeniedException.class);

        verifyNoInteractions(deletionRepository, deletedPublisher, auditRecorder);
    }

    @Test
    @DisplayName("없는 방은 403 이다 — 없는 방과 남의 방을 가르지 않는다")
    void anAbsentRoomIsAPermissionDenial() {
        given(liveRoomReader.findRoom(roomId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(by(sellerId, "SELLER")))
                .isInstanceOf(ChatPermissionDeniedException.class);
        verifyNoInteractions(deletionRepository);
    }

    @Test
    @DisplayName("모르는 역할은 403 이다")
    void anUnknownRoleIsDenied() {
        roomIsLive(true);

        assertThatThrownBy(() -> service.delete(by(sellerId, "SUPERUSER")))
                .isInstanceOf(ChatPermissionDeniedException.class);
        verifyNoInteractions(deletionRepository);
    }

    @Test
    @DisplayName("⭐ 그 방에 없는 메시지(다른 방 · 없는 id)는 404 이고 아무것도 쓰지 않는다")
    void aMessageOutsideTheRoomIsNotFound() {
        roomIsLive(true);
        given(deletionRepository.findInRoom(roomId, messageId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(by(sellerId, "SELLER")))
                .isInstanceOf(ChatMessageNotFoundException.class);
        verify(deletionRepository, never()).markDeleted(any(), anyString(), any(), any());
        verifyNoInteractions(deletedPublisher, auditRecorder);
    }

    @Test
    @DisplayName("⭐ 방 주인은 ADMIN 의 메시지를 지우지 못한다 — 표시 전에 403")
    void theOwnerCannotDeleteAnAdminsMessage() {
        roomIsLive(true);
        messageBy(ChatRole.ADMIN, false);

        assertThatThrownBy(() -> service.delete(by(sellerId, "SELLER")))
                .isInstanceOf(ChatPermissionDeniedException.class);
        verify(deletionRepository, never()).markDeleted(any(), anyString(), any(), any());
        verifyNoInteractions(deletedPublisher, auditRecorder);
    }

    @Test
    @DisplayName("⭐ 발행이 실패해도 감사 행은 이미 남았다 — 재시도는 표시를 보고 발행만 다시 한다")
    void aPublishFailureStillLeavesTheAuditRow() {
        roomIsLive(true);
        messageBy(ChatRole.BUYER, false);
        given(deletionRepository.markDeleted(roomId, messageId, sellerId, NOW)).willReturn(true);
        willThrow(new IllegalStateException("redis down")).given(deletedPublisher).publishDeleted(roomId, messageId);

        assertThatThrownBy(() -> service.delete(by(sellerId, "SELLER"))).isInstanceOf(IllegalStateException.class);
        verify(auditRecorder).record(any());
    }

    @Test
    @DisplayName("감사 쓰기가 실패하면 전파하고 발행하지 않는다")
    void anAuditFailurePropagates() {
        roomIsLive(true);
        messageBy(ChatRole.BUYER, false);
        given(deletionRepository.markDeleted(roomId, messageId, sellerId, NOW)).willReturn(true);
        willThrow(new IllegalStateException("db down")).given(auditRecorder).record(any());

        assertThatThrownBy(() -> service.delete(by(sellerId, "SELLER"))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(deletedPublisher);
    }
}
