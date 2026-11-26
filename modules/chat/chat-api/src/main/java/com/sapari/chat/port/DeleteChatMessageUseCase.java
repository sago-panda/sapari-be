package com.sapari.chat.port;

import com.sapari.chat.command.DeleteChatMessageCommand;

/**
 * 방 주인 또는 관리자가 메시지 한 건을 지운다. 원문은 남기고 삭제 표시만 단다.
 *
 * <p>이미 지운 메시지를 다시 지우는 요청은 성공이다.
 */
@BlockingChat
public interface DeleteChatMessageUseCase {

    void delete(DeleteChatMessageCommand command);
}
