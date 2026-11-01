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

    /**
     * Base64 자체가 깨진 입력은 사유를 밝히고 실패한다.
     *
     * <p>⚠️ <b>발화 조건이 좁다.</b> 개행을 허용하려고 MIME 디코더를 쓰므로 비-base64 <i>문자</i>는
     * 그냥 무시되고(실측: {@code "이건 base64 가 아니다!!"} → 4바이트로 디코드) 그 결과가 아래
     * "RSA 공개키가 아니다" 분기로 떨어진다 — 그쪽이 더 정확한 진단이다. 이 분기가 걸리는 것은
     * <b>패딩·길이가 깨진</b> 입력이다(실측: {@code "A"} → "Last unit does not have enough valid bits").
     */
    @Test
    @DisplayName("Base64 길이·패딩이 깨지면 사유를 밝히고 실패한다 — 디코더 예외로 죽으면 원인이 안 읽힌다")
    void malformed_base64_fails_with_a_reason() {
        assertThatThrownBy(() -> config.roomTokenPublicKey("A"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }

    @Test
    @DisplayName("비-base64 문자가 섞인 값은 키 분기로 떨어진다 — MIME 디코더가 문자를 무시한다")
    void garbage_characters_fall_through_to_the_key_branch() {
        assertThatThrownBy(() -> config.roomTokenPublicKey("이건 base64 가 아니다!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RSA");
    }

    @Test
    @DisplayName("RSA 공개키가 아니면 사유를 밝히고 실패한다 — 한 줄 개인키를 넣는 실수가 여기서 걸린다")
    void non_rsa_public_key_fails_with_a_reason() {
        assertThatThrownBy(() -> config.roomTokenPublicKey(
                Base64.getEncoder().encodeToString("not a key".getBytes())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RSA");
    }

    @Test
    @DisplayName("⭐ 올바른 공개키는 바이트까지 그대로 로드된다 — 검증만 하고 새 키를 돌려주면 안 된다")
    void valid_key_is_restored_exactly() throws Exception {
        byte[] spki = freshPublicKeyBytes();
        String oneLine = Base64.getEncoder().encodeToString(spki);

        // 바이트 동일성까지 본다. isNotNull() 만 보면 "검증은 하고 엉뚱한 키를 돌려주는" 뮤턴트가
        // 이 테스트와 검증기 테스트를 전부 통과하고, 실제 토큰 검증이 깨지는 것은 컨테이너를 띄우는
        // 먼 테스트에서 원인을 가린 신호로만 드러난다(리뷰어 실측).
        assertThat(config.roomTokenPublicKey(oneLine).getEncoded()).isEqualTo(spki);
    }

    /**
     * ⭐ 설정 템플릿이 지시하는 값은 <b>PEM 본문</b>이고 그건 64열로 줄바꿈된 문자열이다.
     *
     * <p>개행을 거부하는 디코더를 쓰면 유효한 base64 를 "Base64 가 아니다"로 오진한다 — 이 가드의
     * 목적이 친절한 실패인데 가장 흔한 입력 모양에서 바로 그 목적이 깨진다.
     */
    @Test
    @DisplayName("⭐ 줄바꿈된 PEM 본문도 로드된다 — 템플릿이 지시하는 모양이다")
    void pem_body_with_newlines_loads() throws Exception {
        byte[] spki = freshPublicKeyBytes();
        String pemBody = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(spki);

        assertThat(pemBody).as("픽스처가 여러 줄이 아니면 이 테스트가 아무것도 재지 않는다").contains("\n");
        assertThat(config.roomTokenPublicKey(pemBody).getEncoded())
                .as("줄바꿈된 PEM 본문을 거부했다 — 템플릿이 지시하는 값인데 오진한다")
                .isEqualTo(spki);
    }

    private static byte[] freshPublicKeyBytes() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair().getPublic().getEncoded();
    }
}
