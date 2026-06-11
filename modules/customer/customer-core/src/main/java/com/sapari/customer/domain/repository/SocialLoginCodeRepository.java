package com.sapari.customer.domain.repository;

import java.util.Optional;

/**
 * 소셜 로그인 임시 코드 저장소 포트.
 * application 서비스는 이 포트에만 의존하고, Redis 구현은 infrastructure가 제공한다.
 */
public interface SocialLoginCodeRepository {

    void save(String code, String value);

    Optional<String> findByCode(String code);

    void delete(String code);
}
