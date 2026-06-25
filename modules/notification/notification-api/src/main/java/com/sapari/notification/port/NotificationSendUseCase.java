package com.sapari.notification.port;

import com.sapari.notification.command.SendSignupVerificationSmsCommand;
import com.sapari.notification.command.SendSignupVerificationEmailCommand;
import com.sapari.notification.view.MessageSendResult;

/**
 * 사용자 대상 메시지 발송을 담당하는 notification 공개 포트다.
 * 호출자는 provider SDK나 템플릿 구현을 직접 의존하지 않는다.
 */
public interface NotificationSendUseCase {

    /**
     * 회원가입 인증 SMS 템플릿을 notification에서 렌더링한 뒤 발송한다.
     * 호출 도메인은 인증번호 생성·검증 정책만 소유하고, 사용자 노출 문구는 notification이 소유한다.
     */
    MessageSendResult sendSignupVerificationSms(SendSignupVerificationSmsCommand command);

    /**
     * 회원가입 인증 이메일 템플릿을 notification에서 렌더링한 뒤 발송한다.
     * 호출 도메인은 인증번호 생성·검증 정책만 소유하고, 사용자 노출 문구는 notification이 소유한다.
     */
    MessageSendResult sendSignupVerificationEmail(SendSignupVerificationEmailCommand command);
}
