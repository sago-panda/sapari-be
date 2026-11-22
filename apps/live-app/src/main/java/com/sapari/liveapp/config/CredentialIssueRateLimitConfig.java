package com.sapari.liveapp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sapari.liveapp.security.CredentialIssueRateLimitFilter;
import com.sapari.liveapp.security.CredentialIssueRateLimiter;
import com.sapari.liveapp.security.IngressReissueRateLimitProperties;
import com.sapari.liveapp.security.ThumbnailUploadRateLimitProperties;

/**
 * 판매자 자격증명 발급(썸네일 업로드 허가 · 송출 키 재발급) 상한 배선.
 *
 * <p>필터는 빈으로 내고 {@code LiveSecurityConfig} 가 JWT 필터 뒤에 끼운다. 판매자 {@code sub} 로
 * 샤드를 접으므로 인증 뒤여야 한다.
 *
 * <p>⚠️ Boot 는 {@code Filter} 빈을 서블릿 필터로도 자동 등록하므로 이 필터는 요청마다 두 번 불린다.
 * 시큐리티 체인 안의 호출이 먼저 돌아 세고, 뒤이은 서블릿 필터 호출은 {@code OncePerRequestFilter}
 * 가 건너뛴다 — 두 번 세지 않는 근거는 그것이다. 형제 상한 필터들도 같은 구조다.
 *
 * <p>⚠️ {@code @ConditionalOnProperty} 를 두지 않았다 — 끄는 길을 만들면 "상한 없이 도는 파드" 가
 * 설정 하나로 가능해진다. 빠져나갈 길은 한도를 올리는 것이고, 잘못된 값은 여기서 부팅을 막는다.
 *
 * <p>두 경로에 리미터를 <b>따로</b> 준다. 하나를 공유하면 한쪽의 폭주가 다른 쪽 예산을 먹는다 —
 * {@code ChatHistoryPaths} javadoc 이 적는 그 결함 모양이고, 증폭 계수도 5배 차이가 난다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ThumbnailUploadRateLimitProperties.class, IngressReissueRateLimitProperties.class})
public class CredentialIssueRateLimitConfig {

    @Bean
    public CredentialIssueRateLimitFilter credentialIssueRateLimitFilter(
            ThumbnailUploadRateLimitProperties thumbnail, IngressReissueRateLimitProperties reissue) {
        return new CredentialIssueRateLimitFilter(
                new CredentialIssueRateLimiter("썸네일 업로드 허가", thumbnail.limits()),
                new CredentialIssueRateLimiter("송출 키 재발급", reissue.limits()));
    }
}
