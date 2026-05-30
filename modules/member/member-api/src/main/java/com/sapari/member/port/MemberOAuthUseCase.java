package com.sapari.member.port;

import com.sapari.member.command.MemberOAuthCommand;
import com.sapari.member.result.MemberOAuthResult;

public interface MemberOAuthUseCase {

    MemberOAuthResult handleOAuthSuccess(MemberOAuthCommand command);
}
