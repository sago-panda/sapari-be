package com.sapari.chat.port;

import com.sapari.chat.command.GetChatHistoryCommand;
import com.sapari.chat.view.GetChatHistoryView;

public interface GetChatHistoryUseCase {

    /** 채팅 이력을 조회한다(강퇴자 제외 + 이메일 게이팅). blocking — live-app(MVC)에서 호출. */
    GetChatHistoryView getHistory(GetChatHistoryCommand command);
}
