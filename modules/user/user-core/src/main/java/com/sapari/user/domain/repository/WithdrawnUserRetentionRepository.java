package com.sapari.user.domain.repository;

import java.time.Instant;
import java.util.UUID;

import com.sapari.user.domain.model.WithdrawnUserRetention;

public interface WithdrawnUserRetentionRepository {

    /**
     * 탈퇴회원 법정 보존 정보를 저장한다.
     */
    WithdrawnUserRetention save(WithdrawnUserRetention withdrawnUserRetention);

    /**
     * 동일 원 사용자에 대한 보존 row가 이미 있는지 확인해 중복 생성을 방지한다.
     */
    boolean existsByOriginalUserId(UUID originalUserId);

    /**
     * 보존 만료 시각이 기준 시각 이하인 row를 삭제한다.
     */
    int deleteExpiredBefore(Instant now);

    /**
     * 원 사용자 ID로 보존 row를 삭제한다.
     */
    int deleteByOriginalUserId(UUID originalUserId);
}
