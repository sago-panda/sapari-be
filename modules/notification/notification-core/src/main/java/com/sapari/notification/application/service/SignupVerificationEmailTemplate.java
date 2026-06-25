package com.sapari.notification.application.service;

import org.springframework.stereotype.Component;

/**
 * 회원가입 이메일 인증번호 메일 문구를 렌더링한다.
 * 사용자에게 노출되는 가입 인증 문구는 user가 아니라 notification에서 소유한다.
 */
@Component
public class SignupVerificationEmailTemplate {

    private static final String SUBJECT = "Sapari 이메일 인증번호";

    /**
     * 이메일 제목은 notification 템플릿이 소유해 user/seller/customer flow가 문구를 중복 관리하지 않게 한다.
     */
    public String subject() {
        return SUBJECT;
    }

    /**
     * 인증번호는 메일 본문에만 포함하고 로그/응답/Redis에는 남기지 않는다는 전제의 사용자 안내 HTML이다.
     */
    public String render(String verificationCode) {
        return """
                <p>Sapari 회원가입 이메일 인증번호입니다.</p>
                <p>인증번호는 <strong>%s</strong> 입니다.</p>
                <p>인증번호는 5분 동안만 유효합니다.</p>
                <p>본 메일은 회원가입 이메일 인증을 위해 발송되었습니다.</p>
                <p>문의가 필요하면 contact@ascode.click 로 연락해 주세요.</p>
                """.formatted(verificationCode);
    }
}
