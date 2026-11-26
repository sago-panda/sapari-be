package com.sapari.chat.application.port;

import java.util.UUID;

import com.sapari.chat.port.BlockingChat;

/**
 * 메시지 삭제를 그 방의 모든 Pod 에 알리는 포트. <b>블로킹</b> — 호출자가 live-app(MVC)이다.
 *
 * <p>강퇴 발행({@link ChatKickEventPublisher})과 같은 방 채널을 쓴다. 실패는 전파한다 — 삭제 표시는 이미
 * 저장돼 있으므로 다시 호출하면 표시는 그대로 두고 발행만 한 번 더 한다.
 */
@BlockingChat
public interface ChatMessageDeletedPublisher {

    void publishDeleted(UUID roomId, String messageId);
}
