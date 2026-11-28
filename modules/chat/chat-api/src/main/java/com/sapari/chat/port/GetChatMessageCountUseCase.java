package com.sapari.chat.port;

import java.util.UUID;

import com.sapari.chat.exception.ChatMessageCountUnavailableException;

/**
 * 방 하나의 채팅 수 — 다른 도메인(live 통계)이 chat 저장소를 직접 읽지 않고 이 값을 얻는 길이다.
 *
 * <p>blocking — live-app(MVC)에서 호출한다.
 *
 * <p><b>세는 것</b>: 일반(NORMAL) 메시지 중 삭제 표시가 없는 것. 공지와 삭제된 메시지는 빼고, 강퇴된 사람의
 * 메시지는 센다 — 그 메시지는 쓰인 때 화면에 보였다. 그래서 이력 조회(강퇴자를 뺀다)와 수가 다를 수 있다.
 */
@BlockingChat
public interface GetChatMessageCountUseCase {

    /**
     * 메시지가 없는 방(없는 방 포함)은 0.
     *
     * @throws ChatMessageCountUnavailableException 저장소 장애. 0 으로 바꿔 돌려주지 않는다 — 장애가
     *         "채팅 없음" 으로 통계에 남는다.
     */
    long countMessages(UUID roomId);
}
