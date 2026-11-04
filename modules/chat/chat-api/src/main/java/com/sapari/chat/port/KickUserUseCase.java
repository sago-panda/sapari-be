package com.sapari.chat.port;

import com.sapari.chat.command.KickUserCommand;

public interface KickUserUseCase {

    /** 대상 유저를 방에서 강퇴하고 ban 카운트를 누적한다. blocking — live-app(MVC)에서 호출. */
    void kick(KickUserCommand command);
}
