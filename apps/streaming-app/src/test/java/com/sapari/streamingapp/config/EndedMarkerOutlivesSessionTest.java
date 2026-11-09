package com.sapari.streamingapp.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.infrastructure.redis.ChatRoomEndedRedisRepository;

/**
 * 세션 수명 ≤ 방 종료 마커 보관 기간을 기동에서 강제한다.
 *
 * <p>종료 신호를 놓친 Pod 의 세션을 막는 것은 전송 경로의 마커 재확인뿐이라, 마커가 수명보다 먼저
 * 사라지면 그 뒤의 전송이 종료 판정을 통과한다. 이 검사가 생기기 전에는 마커 30분 · 수명 60분이었다.
 */
@DisplayName("세션 수명과 방 종료 마커")
class EndedMarkerOutlivesSessionTest {

    private static final Duration PING = Duration.ofSeconds(30);
    private static final Duration DEADLINE = Duration.ofSeconds(90);

    @Test
    @DisplayName("⭐ 기본 수명 60분이 지금 마커로 기동한다 — 마커를 수명 아래로 내리면 여기가 빨강")
    void defaultLifetimeBootsWithTheCurrentMarker() {
        assertThatCode(() -> new WebSocketConfig().sessionTimeouts(Duration.ofMinutes(60), PING, DEADLINE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⭐ 수명이 마커보다 1초라도 길면 기동이 멈춘다")
    void lifetimeLongerThanTheMarkerFailsTheBoot() {
        Duration lifetime = ChatRoomEndedRedisRepository.ENDED_TTL.plusSeconds(1);

        assertThatThrownBy(() -> new WebSocketConfig().sessionTimeouts(lifetime, PING, DEADLINE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("종료 마커");
    }

    @Test
    @DisplayName("수명이 마커와 같으면 통과한다 — 마커가 세션보다 먼저 사라지지 않는다")
    void lifetimeEqualToTheMarkerBoots() {
        assertThatCode(() -> new WebSocketConfig()
                .sessionTimeouts(ChatRoomEndedRedisRepository.ENDED_TTL, PING, DEADLINE))
                .doesNotThrowAnyException();
    }
}
