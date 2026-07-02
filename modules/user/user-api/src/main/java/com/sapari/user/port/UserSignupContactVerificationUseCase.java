package com.sapari.user.port;

import com.sapari.user.command.SignupContactVerificationConsumeCommand;

/**
 * 회원가입 최종 저장 전에 필요한 휴대폰·이메일 verified 상태를 함께 소비하는 user-api 포트다.
 */
public interface UserSignupContactVerificationUseCase {

    /**
     * 휴대폰과 이메일 verified 상태가 모두 있으면 둘 다 한 번에 소비한다.
     * 하나라도 없으면 아무 verified 상태도 삭제하지 않고 누락된 인증 오류를 던진다.
     */
    void consumeSignupContactVerification(SignupContactVerificationConsumeCommand command);
}
