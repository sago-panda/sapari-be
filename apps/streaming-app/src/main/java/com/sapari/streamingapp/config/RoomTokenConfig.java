package com.sapari.streamingapp.config;

import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

/**
 * 룸 토큰 검증용 live 공개키 빈.
 *
 * <p>운영에서는 {@code chat.room-token.public-key}(live RSA 공개키, Base64 X.509 SubjectPublicKeyInfo)를 설정한다.
 * 미설정 시에는 부팅을 막지 않기 위해 <b>임시(ephemeral) 키</b>를 생성하지만, 이 키로는 live가 발급한 실제 토큰을
 * 검증할 수 없어 모든 입장이 거부된다(조용한 우회가 아니라 전면 거부 = 시끄러운 실패). live 공개키 수령 후 반드시 설정.
 */
@Slf4j
@Configuration
public class RoomTokenConfig {

    @Bean
    public PublicKey roomTokenPublicKey(
            @Value("${chat.room-token.public-key:}") String base64Spki) throws Exception {

        if (base64Spki == null || base64Spki.isBlank()) {
            log.warn("chat.room-token.public-key 미설정 — 임시 RSA 공개키 생성. live 공개키로 교체 전까지 룸 토큰 검증은 전부 실패한다.");
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair().getPublic();
        }

        byte[] der = Base64.getDecoder().decode(base64Spki);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }
}
