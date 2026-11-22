package com.sapari.customer.domain.repository;

import java.util.Optional;

/**
 * 소셜 로그인 임시 코드 저장소 포트.
 * application 서비스는 이 포트에만 의존하고, Redis 구현은 infrastructure가 제공한다.
 */
public interface SocialLoginCodeRepository {

    void save(String code, String value);

    /**
     * 임시 로그인 code를 한 번만 소비한다.
     * 값이 존재하면 반환과 동시에 삭제하고, 없거나 이미 소비된 code면 Optional.empty()를 반환한다.
     */
    Optional<String> consumeByCode(String code);
}
