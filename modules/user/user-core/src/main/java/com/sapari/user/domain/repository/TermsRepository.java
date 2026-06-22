package com.sapari.user.domain.repository;

import java.time.Instant;
import java.util.Optional;

import com.sapari.user.domain.model.Terms;
import com.sapari.user.model.TermsType;

public interface TermsRepository {

    /**
     * 가입 처리에서 사용할 현재 유효 약관 버전을 조회한다.
     * active=true는 현재 유효 약관 1개라는 운영 상태이고, effectiveAt 조건은 미래 약관 오등록을 방어한다.
     */
    Optional<Terms> findActiveByTypeEffectiveAt(TermsType type, Instant effectiveAt);
}
