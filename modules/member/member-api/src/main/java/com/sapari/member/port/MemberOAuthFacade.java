package com.sapari.member.port;

import com.sapari.member.command.MemberOAuthCommand;
import com.sapari.member.result.MemberOAuthResult;

public interface MemberOAuthFacade {

    MemberOAuthResult handleOAuthSuccess(MemberOAuthCommand command);
}
