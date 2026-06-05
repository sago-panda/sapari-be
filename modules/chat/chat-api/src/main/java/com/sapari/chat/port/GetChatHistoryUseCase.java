package com.sapari.chat.port;

import com.sapari.chat.command.GetChatHistoryCommand;
import com.sapari.chat.view.GetChatHistoryResult;

public interface GetChatHistoryUseCase {

    /** 채팅 이력을 조회한다(강퇴자 제외 + 이메일 게이팅). blocking — api-app(MVC)에서 호출. */
    GetChatHistoryResult getHistory(GetChatHistoryCommand command);
}
