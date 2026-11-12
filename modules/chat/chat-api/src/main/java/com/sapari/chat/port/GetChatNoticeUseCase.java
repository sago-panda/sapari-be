package com.sapari.chat.port;

import java.util.Optional;

import com.sapari.chat.command.GetChatNoticeCommand;
import com.sapari.chat.view.ChatMessageView;

public interface GetChatNoticeUseCase {

    /** 방의 가장 최근 공지 한 건(강퇴자 제외 + 이메일 게이팅). 없으면 빈 값. blocking — live-app(MVC)에서 호출. */
    Optional<ChatMessageView> findLatestNotice(GetChatNoticeCommand command);
}
