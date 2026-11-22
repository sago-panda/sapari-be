package com.sapari.liveapp.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 시큐리티 체인을 세우는 테스트가 쓰는 발급 상한 — 사실상 걸리지 않는 한도다. */
public final class CredentialIssueRateLimitFixtures {

    private CredentialIssueRateLimitFixtures() {
    }

    public static CredentialIssueRateLimitFilter unlimited() {
        CredentialIssueLimits limits = new CredentialIssueLimits(1_000_000, 1_000_000, 1);
        return new CredentialIssueRateLimitFilter(
                new CredentialIssueRateLimiter("테스트 썸네일", limits),
                new CredentialIssueRateLimiter("테스트 재발급", limits));
    }

    /**
     * 웹 슬라이스용 — 기본 한도(썸네일 버스트 48)를 쓰면 컨텍스트가 테스트 메서드 사이에 공유돼
     * 버킷이 바닥나 엉뚱한 429 가 난다. 상한 자체는 {@code CredentialIssueRateLimitFilterTest} 가 본다.
     */
    @TestConfiguration(proxyBeanMethods = false)
    public static class UnlimitedConfig {
        @Bean
        public CredentialIssueRateLimitFilter credentialIssueRateLimitFilter() {
            return unlimited();
        }
    }
}
