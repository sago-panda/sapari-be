package com.sapari.liveapp.controller.chat;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.sapari.chat.command.DeleteChatMessageCommand;
import com.sapari.chat.port.DeleteChatMessageUseCase;
import com.sapari.liveapp.security.LiveUserPrincipal;

import lombok.RequiredArgsConstructor;

/**
 * 메시지 한 건을 지운다 — 방 주인과 관리자. 원문은 남고 이력에서 빠지며, 접속 중인 화면에는
 * {@code MESSAGE_DELETED} 가 간다.
 *
 * <p><b>경로가 {@code /api/v1/lives/**} 밖인 이유.</b> 그 아래는 {@code LiveSecurityConfig} 의 판매자 전용 바닥에
 * 걸려 관리자가 403 을 받는다(실측). 강퇴처럼 그 앞에 규칙을 두려면 live 의 보안 설정을 고쳐야 해서, 역할 바닥이 없는
 * {@code /api/v1/chat/**}(인증만 본다)에 둔다. 그래서 이 경로의 역할 관문은 보안 필터가 아니라
 * {@code DeleteChatMessageService} 의 첫 검사다 — 방 주인도 관리자도 아니면 메시지를 읽기 전에 403 이다.
 *
 * <p>성공은 204 이고 이미 지운 메시지도 204 다. 그 방에 없는 메시지(다른 방의 id 포함)는 404, 권한 없음은 403.
 * 끝난 방도 지울 수 있다 — 다시보기 채팅이 같은 이력을 읽는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/chat/rooms/{roomId}/messages")
public class ChatMessageController {

    private final DeleteChatMessageUseCase deleteChatMessageUseCase;

    @DeleteMapping("/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID roomId,
                       @PathVariable String messageId,
                       @AuthenticationPrincipal LiveUserPrincipal principal) {
        deleteChatMessageUseCase.delete(new DeleteChatMessageCommand(
                roomId, principal.userId(), principal.role(), messageId));
    }
}
