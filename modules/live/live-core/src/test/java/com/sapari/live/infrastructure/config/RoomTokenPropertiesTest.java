package com.sapari.live.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 룸 토큰 수명 상한 — chat 이 이 수를 믿고 탈퇴 기록 TTL 을 정한다(SPR-185).
 */
class RoomTokenPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Bind.class)
            .withPropertyValues(
                    "live.room-token.issuer=live",
                    "live.room-token.audience=chat",
                    "live.room-token.private-key=dummy");

    @Test
    @DisplayName("⭐ 토큰 수명이 300초를 넘으면 기동하지 않는다")
    void anExpirationOver300SecondsRefusesToBoot() {
        runner.withPropertyValues("live.room-token.expiration-seconds=301")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("300초는 기동한다 — 상한은 포함이다")
    void exactly300SecondsBoots() {
        runner.withPropertyValues("live.room-token.expiration-seconds=300")
                .run(context -> assertThat(context).hasNotFailed()
                        .getBean(RoomTokenProperties.class)
                        .extracting(RoomTokenProperties::expirationSeconds)
                        .isEqualTo(300L));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RoomTokenProperties.class)
    static class Bind {
    }
}
