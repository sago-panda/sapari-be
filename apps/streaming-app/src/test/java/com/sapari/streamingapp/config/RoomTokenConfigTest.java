package com.sapari.streamingapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 이 빈 하나가 채팅 입장 인증의 전부다 — 룸 토큰 검증에 쓰는 live 공개키를 만든다.
 *
 * <p>공개키가 없을 때 어떻게 하느냐가 보안상의 갈림길이다. 부팅을 막으면 live 키를 못 받은 환경이 아예
 * 안 뜨고, 검증을 건너뛰면 <b>아무 토큰이나 통과</b>한다. 그래서 임시 키를 만들어 부팅은 시키되 그 키로는
 * 실제 토큰이 하나도 통과하지 못하게 한다 — 조용한 우회가 아니라 시끄러운 전면 거부다.
 * 이 파일은 그 선택이 뒤집히지 않게 잡아둔다.
 */
@DisplayName("RoomTokenConfig — 공개키가 없으면 조용히 열지 않고 전면 거부한다")
class RoomTokenConfigTest {

    private final RoomTokenConfig config = new RoomTokenConfig();

    @ParameterizedTest(name = "public-key=[{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("미설정 → 부팅은 되지만 live가 서명한 토큰과 짝이 맞지 않는 임시 키가 나온다")
    void missingKey_yieldsEphemeralKey(String value) throws Exception {
        // when
        PublicKey key = config.roomTokenPublicKey(value);

        // then: 키 자체는 유효한 RSA다(부팅을 막지 않는다). 다만 live의 개인키와 짝이 아니라
        // 검증이 전부 실패한다 — 그래서 "설정을 깜빡했다"가 "인증이 꺼졌다"로 이어지지 않는다.
        assertThat(key).isNotNull();
        assertThat(key.getAlgorithm()).isEqualTo("RSA");
    }

    @Test
    @DisplayName("미설정으로 두 번 만들면 서로 다른 키 — 임시 키는 고정 백도어가 아니다")
    void ephemeralKeys_areNotStable() throws Exception {
        // when
        PublicKey first = config.roomTokenPublicKey("");
        PublicKey second = config.roomTokenPublicKey("");

        // then: 매번 새로 생성되므로 이 값을 알아내 토큰을 위조하는 경로가 성립하지 않는다
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("정상 Base64 SPKI → 그 공개키를 그대로 복원한다(live가 준 키를 손대지 않는다)")
    void validKey_isRestoredExactly() throws Exception {
        // given
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        String base64Spki = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());

        // when
        PublicKey restored = config.roomTokenPublicKey(base64Spki);

        // then: 바이트가 한 곳이라도 달라지면 live 토큰이 통째로 거부된다
        assertThat(restored).isEqualTo(pair.getPublic());
        assertThat(restored.getEncoded()).isEqualTo(pair.getPublic().getEncoded());
    }

    @Test
    @DisplayName("깨진 값 → 부팅 실패. 잘못 설정한 키를 임시 키로 덮으면 원인을 못 찾는다")
    void malformedKey_failsFast() {
        // when & then: 여기서 조용히 임시 키로 넘어가면 "설정했는데 아무도 입장이 안 된다"는
        // 증상만 남고 오타는 로그에 안 남는다. 미설정(=경고 후 임시 키)과 오설정은 다르게 다룬다.
        assertThatThrownBy(() -> config.roomTokenPublicKey("이건-base64가-아니다"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
