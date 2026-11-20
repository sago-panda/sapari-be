package com.sapari.live.infrastructure.config;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.RoomTokenIssuer;
import com.sapari.live.infrastructure.media.CachingRoomTokenIssuer;
import com.sapari.live.infrastructure.media.RoomTokenProvider;

/**
 * 룸 토큰 RS256 서명용 개인키 빈을 구성한다.
 *
 * <p>{@link RoomTokenProperties#privateKey()}(Base64 PKCS#8)를 앱 기동 시 1회 파싱해 {@link PrivateKey}로
 * 제공한다 — 요청마다 파싱하지 않도록 빈으로 캐싱. 키 형식이 잘못되면 기동 단계에서 실패시킨다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RoomTokenProperties.class, RoomTokenCacheProperties.class})
public class RoomTokenConfig {

    @Bean
    public PrivateKey roomTokenPrivateKey(RoomTokenProperties properties) {
        try {
            byte[] der = Base64.getDecoder().decode(properties.privateKey());
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            // 개인키 원문은 자격증명이므로 예외 메시지에 싣지 않는다.
            throw new IllegalStateException("룸 토큰 개인키(live.room-token.private-key) 파싱 실패", e);
        }
    }

    /**
     * 서명 캐시를 {@code @Primary} 로 앞에 세운다 — 형제 {@code LiveMetricsConfig} 가 계측
     * 데코레이터에 쓰는 것과 같은 모양이다. {@code EnterLiveService} 는 자기가 감싸진 걸 모른다.
     *
     * <p><b>꺼져 있으면 원본을 그대로 돌려준다</b> — 호출 경로에 데코레이터가 0줄 남는다.
     * {@code @ConditionalOnProperty} 를 쓰지 않은 이유도 형제와 같다: 그 조건은 사용자
     * {@code @Configuration} 이 자동 구성보다 먼저 평가되는 순서 의존 함정이고, 여기서는 그냥
     * 분기 하나로 끝난다.
     *
     * <p>delegate 를 <b>구체 타입으로 받는다</b>({@link RoomTokenProvider}). 포트 타입으로 받으면
     * {@code @Primary} 인 자기 자신이 후보가 되어 자기주입이 된다.
     *
     * <p>캐시 TTL 과 발급 TTL 의 관계는 여기서 검사한다 — 두 레코드에 걸친 불변식이라 어느 한쪽의
     * 컴팩트 생성자에서는 볼 수 없다({@link RoomTokenCacheProperties#validateAgainst}).
     */
    @Bean
    @Primary
    public RoomTokenIssuer cachingRoomTokenIssuer(RoomTokenProvider delegate, LiveMetrics metrics,
                                                  RoomTokenProperties issuance,
                                                  RoomTokenCacheProperties cache) {
        cache.validateAgainst(issuance);
        return cache.enabled()
                ? new CachingRoomTokenIssuer(delegate, metrics, cache, System::nanoTime)
                : delegate;
    }
}
