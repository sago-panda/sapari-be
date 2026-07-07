package com.sapari.live.infrastructure.config;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 룸 토큰 RS256 서명용 개인키 빈을 구성한다.
 *
 * <p>{@link RoomTokenProperties#privateKey()}(Base64 PKCS#8)를 앱 기동 시 1회 파싱해 {@link PrivateKey}로
 * 제공한다 — 요청마다 파싱하지 않도록 빈으로 캐싱. 키 형식이 잘못되면 기동 단계에서 실패시킨다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RoomTokenProperties.class)
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
}
