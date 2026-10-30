package com.sapari.streamingapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 공개키 설정 가드. 컨텍스트를 세우지 않고 빈 메서드를 직접 부른다 — 같은 모양의 선례가 있다
 * ({@code ManagementPortGuardTest}).
 */
@DisplayName("룸 토큰 공개키 — 없거나 틀리면 부팅을 실패시킨다")
class RoomTokenConfigTest {

    private final RoomTokenConfig config = new RoomTokenConfig();

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("⭐ 값이 없으면 부팅이 실패한다 — 뜨면 모든 입장이 실패하는 배포가 성공으로 보인다")
    void missing_key_fails_boot(String blank) {
        assertThatThrownBy(() -> config.roomTokenPublicKey(blank))
                .as("임시 키로 대신하고 뜨면, 남는 신호가 경고 한 줄뿐이라 배포가 성공으로 보인다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chat.room-token.public-key");
    }

    @Test
    @DisplayName("값이 null 이어도 같다 — 프로퍼티가 아예 없는 경우")
    void null_key_fails_boot() {
        assertThatThrownBy(() -> config.roomTokenPublicKey(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Base64 가 아니면 사유를 밝히고 실패한다 — 디코더 예외로 죽으면 원인이 안 읽힌다")
    void non_base64_fails_with_a_reason() {
        assertThatThrownBy(() -> config.roomTokenPublicKey("이건 base64 가 아니다!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }

    @Test
    @DisplayName("RSA 공개키가 아니면 사유를 밝히고 실패한다 — 개인키·인증서를 넣는 실수가 여기서 걸린다")
    void non_rsa_public_key_fails_with_a_reason() {
        assertThatThrownBy(() -> config.roomTokenPublicKey(
                Base64.getEncoder().encodeToString("not a key".getBytes())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RSA");
    }

    @Test
    @DisplayName("올바른 공개키는 그대로 로드된다")
    void valid_key_loads() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        String spki = Base64.getEncoder().encodeToString(gen.generateKeyPair().getPublic().getEncoded());

        assertThat(config.roomTokenPublicKey(spki)).isNotNull();
    }
}
