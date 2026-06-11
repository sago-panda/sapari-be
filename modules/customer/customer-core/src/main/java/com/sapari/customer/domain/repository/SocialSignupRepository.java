package com.sapari.customer.domain.repository;

import java.util.Optional;

/**
 * 소셜 회원가입 진행 정보 저장소 포트.
 * application 서비스는 이 포트에만 의존하고, Redis 구현은 infrastructure가 제공한다.
 */
public interface SocialSignupRepository {

    void save(String sid, String value);

    Optional<String> findBySid(String sid);

    void delete(String sid);

    boolean exists(String sid);
}
