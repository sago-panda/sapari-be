package com.sapari.user.domain.repository;

import com.sapari.user.domain.model.UserTermsAgreement;

public interface UserTermsAgreementRepository {

    /**
     * 가입 성공 트랜잭션 안에서 약관 증적을 저장한다.
     */
    UserTermsAgreement save(UserTermsAgreement agreement);

    /**
     * 가입 보상 시 해당 사용자의 약관 증적을 user row보다 먼저 제거한다.
     */
    void deleteByUserId(java.util.UUID userId);
}
