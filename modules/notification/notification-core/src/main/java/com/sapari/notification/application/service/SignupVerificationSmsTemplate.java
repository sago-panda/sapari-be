package com.sapari.notification.application.service;

import org.springframework.stereotype.Component;

/**
 * 회원가입 연락처 인증 SMS의 사용자 노출 문구를 소유한다.
 * 인증번호 생성·검증 정책은 user가 담당하고, notification은 발송 채널별 표현을 관리한다.
 */
@Component
public class SignupVerificationSmsTemplate {

    /**
     * 회원가입 인증번호 SMS 본문을 렌더링한다.
     * 인증번호 유효 시간은 user 인증 정책과 동일하게 5분으로 고정해 안내한다.
     */
    public String render(String verificationCode) {
        return "[Sapari] 인증번호는 " + verificationCode + "입니다. 5분 내 입력해주세요.";
    }
}
