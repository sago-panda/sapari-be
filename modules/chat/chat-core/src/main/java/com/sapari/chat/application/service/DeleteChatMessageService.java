package com.sapari.chat.application.service;

import java.util.Map;

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
import com.sapari.chat.port.DeleteChatMessageUseCase;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.port.GetLiveRoomUseCase;
import com.sapari.live.view.LiveRoomView;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 메시지 삭제 — 표시를 달고, 감사에 남기고, 그 방의 모든 Pod 에 알린다.
 *
 * <p><b>스테레오타입을 달지 않는다.</b> 강퇴({@link KickUserService})와 같은 이유로 블로킹 스택을 가진 앱이 등록한다.
 *
 * <p><b>끝난 방도 지울 수 있다.</b> 강퇴와 달리 진행 여부를 보지 않는다 — 다시보기 채팅도 같은 이력을 읽으므로,
 * 방송이 끝난 뒤에 발견한 메시지도 지울 수 있어야 한다.
 */
@Slf4j
@RequiredArgsConstructor
public class DeleteChatMessageService implements DeleteChatMessageUseCase {

    private final GetLiveRoomUseCase liveRoomReader;
    private final ChatMessageDeletionRepository deletionRepository;
    private final ChatMessageDeletedPublisher deletedPublisher;
    private final ChatAuditRecorder auditRecorder;
    private final ChatPermissionPolicy permissionPolicy;
    private final TimeProvider timeProvider;

    /**
     * {@inheritDoc}
     *
     * <p>메시지 조회가 거친 관문 뒤에 있다 — 방 주인도 관리자도 아니면 메시지를 읽기 전에 끊어서,
     * 권한 없는 호출자가 응답 차이로 메시지 id 가 있는지를 훑지 못하게 한다. 없는 방도 같은 거부다.
     */
    @Override
    public void delete(DeleteChatMessageCommand command) {
        LiveRoomView room = liveRoomReader.findRoom(command.roomId())
                .orElseThrow(() -> new ChatPermissionDeniedException(
                        "메시지를 지울 수 없는 방이다 — roomId=" + command.roomId()));
        ChatRole actorRole = ChatRole.fromPlatformRole(command.actorRole())
                .orElseThrow(() -> new ChatPermissionDeniedException(
                        "알 수 없는 역할이다 — role=" + command.actorRole()));

        if (!room.sellerId().equals(command.actorId()) && actorRole != ChatRole.ADMIN) {
            throw new ChatPermissionDeniedException(
                    "메시지 삭제 권한이 없다 — actor=" + command.actorId() + " room=" + command.roomId());
        }

        ChatMessageDeletionTarget target = deletionRepository.findInRoom(command.roomId(), command.messageId())
                .orElseThrow(() -> new ChatMessageNotFoundException(
                        "그 방에 그 메시지가 없다 — roomId=" + command.roomId()));

        if (!permissionPolicy.canDeleteMessage(actorRole, command.actorId(), room.sellerId(),
                target.senderRole(), target.senderId())) {
            throw new ChatPermissionDeniedException(
                    "메시지 삭제 권한이 없다 — actor=" + command.actorId() + " room=" + command.roomId());
        }

        // 겹친 두 요청이 모두 "아직 안 지움" 을 읽어도 표시는 한쪽만 단다(저장소가 조건부 갱신이다).
        // 감사 행은 표시를 단 쪽만 남긴다.
        boolean marked = !target.deleted()
                && deletionRepository.markDeleted(command.roomId(), command.messageId(),
                        command.actorId(), timeProvider.now());

        // 감사는 표시 바로 뒤, 발행 앞이다. 재시도는 표시가 있어서 감사 행을 다시 쓰지 않으므로, 감사를 발행 뒤에 두면
        // 발행이 실패한 요청의 재시도가 감사 없이 끝난다. 여기 두면 감사 쓰기 자체가 실패할 때만 행이 빠지고,
        // 그 경우의 흔적은 바로 앞의 로그 한 줄이다.
        if (marked) {
            log.info("메시지 삭제 roomId={} messageId={} actor={}",
                    command.roomId(), command.messageId(), command.actorId());
            auditRecorder.record(new ChatAuditEntry(
                    command.actorId(), actorRole, ChatAuditAction.CHAT_DELETE_MESSAGE, command.roomId(),
                    Map.of("messageId", command.messageId(), "senderId", target.senderId().toString()),
                    timeProvider.now()));
        }

        // 이미 지운 메시지여도 다시 알린다. 앞선 발행이 실패했으면 재시도가 그걸 메운다 — 받는 쪽은 이미 지운 id 를
        // 또 지울 뿐이다.
        deletedPublisher.publishDeleted(command.roomId(), command.messageId());
    }
}
