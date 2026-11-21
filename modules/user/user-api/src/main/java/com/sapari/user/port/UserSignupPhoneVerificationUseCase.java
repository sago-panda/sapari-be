package com.sapari.user.port;

import com.sapari.user.command.SignupPhoneVerificationConfirmCommand;
import com.sapari.user.command.SignupPhoneVerificationSendCommand;
import com.sapari.user.view.SignupPhoneVerificationConfirmResult;
import com.sapari.user.view.SignupPhoneVerificationSendResult;

/**
 * 회원가입 휴대폰 소유 검증을 담당하는 user-api 포트다.
 * customer/seller는 user-core가 아니라 이 계약에만 의존한다.
 */
public interface UserSignupPhoneVerificationUseCase {

    /**
     * 회원가입 전에 휴대폰 번호가 실제 사용자 소유인지 확인할 수 있도록 인증번호를 발송한다.
     * 중복 번호 차단, 재요청 쿨다운, 인증번호 저장 정책은 구현체가 서버 기준으로 처리한다.
     */
    SignupPhoneVerificationSendResult sendSignupPhoneVerification(SignupPhoneVerificationSendCommand command);

    /**
     * 사용자가 입력한 인증번호를 서버에 저장된 codeHash와 비교하고 회원가입용 verified 상태를 만든다.
     * verified 상태는 아직 가입 완료가 아니며, 최종 가입 API에서 한 번 더 소비해야 한다.
     */
    SignupPhoneVerificationConfirmResult confirmSignupPhoneVerification(SignupPhoneVerificationConfirmCommand command);

    /**
     * 회원가입 저장 직전에 verified 상태를 1회 소비한다.
     * 클라이언트의 인증 완료 플래그는 신뢰하지 않고 Redis의 서버 상태만 가입 허용 근거로 사용한다.
     */
    void consumeSignupPhoneVerification(String phoneNumber);
}
