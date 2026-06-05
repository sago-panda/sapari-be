package com.sapari.common.web.security;

import java.time.Duration;
import java.util.UUID;

/**
 * 로그아웃된 Access Token을 폐기 목록에 등록하는 포트(쓰기).
 * 폐기 여부 조회는 AccessTokenRevocationChecker(읽기)로 분리한다(CQS).
 * 구현(Redis 등)은 common/auth가 제공한다.
 */
public interface AccessTokenBlacklist {

    void save(UUID tokenId, Duration ttl);
}
