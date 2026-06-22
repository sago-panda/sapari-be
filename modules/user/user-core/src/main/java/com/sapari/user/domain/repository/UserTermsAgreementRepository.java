package com.sapari.user.domain.repository;

import com.sapari.user.domain.model.UserTermsAgreement;

public interface UserTermsAgreementRepository {

    /**
     * 가입 성공 트랜잭션 안에서 약관 증적을 저장한다.
     */
    UserTermsAgreement save(UserTermsAgreement agreement);
}
