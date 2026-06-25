package com.sapari.user.domain.repository;

/**
 * 회원가입 최종 저장 전에 휴대폰과 이메일 verified 상태를 원자적으로 확인·소비한다.
 */
public interface SignupContactVerificationRepository {

    enum ConsumeResult {
        CONSUMED,
        PHONE_MISSING,
        EMAIL_MISSING
    }

    /**
     * 두 verified 상태가 모두 있을 때만 둘 다 삭제한다.
     * 하나라도 없으면 아무 verified 상태도 삭제하지 않는다.
     */
    ConsumeResult consumeVerified(String phoneHash, String emailHash);
}
