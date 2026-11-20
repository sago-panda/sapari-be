package com.sapari.live.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.security.PrivateKey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.RoomTokenIssuer;
import com.sapari.live.infrastructure.media.CachingRoomTokenIssuer;
import com.sapari.live.infrastructure.media.RoomTokenProvider;

class RoomTokenConfigTest {

    private final RoomTokenConfig config = new RoomTokenConfig();

    // dev 로컬 키(application.yaml 기본값과 동일한 형식의 유효한 PKCS#8 Base64)
    private static final String VALID_PKCS8_BASE64 =
            "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCTMscWXy+zK7vJ"
                    + "aY2abBLM5uZZM/rNa/QpnUhS8p5UnG/mJuFNRYovl/5Vk0n71Drx/QkjSmEPwOt2"
                    + "hDG1JVzO/IpgXZiJYyjMJoMW0/lGAxO4zh4/PEXE4pB8dda6638Ivyi48o4qRIJL"
                    + "0ar5OKAhu+nilFU3atrVIHpd9xvQUui9eE1a8ynHuP0II7Mtih2mfzm52Xez2TsW"
                    + "TlccoaBK+6qZkNbxhjxIbbyOvxGyO3qBmgYBUTYRCRFu3WjLnBU+Oi5g0mVRaeDO"
                    + "Ak8jqv2H/W7HetjYxpkEAvfVE94jPpF/GGlstqRv21jfdPcIDyJdplsAfR8aNK7f"
                    + "DUVqWtXNAgMBAAECggEAIn5UK/hrR5u4eibLgYPQ1gZHtWCaZZfmE/hg8dsb4iz0"
                    + "heTXiBGDI8sE1Q3aWPJvS7SldwkffJ8TLmck9NOID5MbZCCatZswfMKLloZe1Bq1"
                    + "fOmEKgJYQR5siFXe11eHIcgV5V0lll8Of3DnFVbBI5aS5L8oxv85v5bIRgu5j1P7"
                    + "Ywz+ygXJszTgz+CfnjdcKgEHqG/mZo0CZ7+QcME+1btjCfvJyGhoejX5te++4Y6i"
                    + "Vy7FjFdi0lKc0+EZ3VPwyO3dcMYPKhyAXC7P7BjBXD0CrLbXu5F67exUitHGYgI4"
                    + "I0tXHoyN2A1pZP7a+VfUofvqHsmBSh1EA5AIhWFEsQKBgQDITFNLyfFrIFfAJCF/"
                    + "ZFtVen5YbKIXuDSpZIPt5Mh4UUW9GOuDFJ8iho68fh+DqOXGVDgtyp01MQlgGBMY"
                    + "Y/vMQsesnN+rM5u6VUR66t+4Shbfjg3vGq4m23LPRUBOs4OTjC55zZ48Lb1NuNSr"
                    + "zOI+hjOdtVsH8ntEkKr8TFU15QKBgQC8IiieIh7t2m/U4tnlU2WzgQ5DTSx1jdFx"
                    + "nkm6hIp96jARb37zQrUxxH9PnNjIqnxafaBxTaqS/Kmzg7coKoT4bfroATdqCs7I"
                    + "wNmyzR+gaCBWRQbsHpMGNYtTLLJX2yNMVh86vm5ObWMFt9y+8lmfaJZzq3jFG0NT"
                    + "xaTriW0hyQKBgEDZBTbKYNEQHZjlmbrG4RMhn3o9YZVQXCxjkJsasRTTK0L3qHg9"
                    + "2u+wpNG9+7ICorG9XprkuFUaVTC5WqVQ6ZrOHBt0hq3E/awsIwmwtVHTGuix8yzw"
                    + "dGW8MsWMZC+WywigIAPrYEmXfWyGZMRihvU7OcbbimdeSC6Ar/sTM5tJAoGBAIqY"
                    + "+6Vr66884nBKY04//0ebxv8r5pn/zZHPk+9133VdxuXBZxwdQ9GTOltTaJ2Eg7JC"
                    + "pKV0GzrIKtkWKyPLF0TR+StcYg+cQLTC5l6EIU2SCGil17Cx4YyMe8Tdw9FXnoyJ"
                    + "Ud58FlVu3qmCx3xgnEgEy/oRFBrZt+MKUzI2fxCJAoGAZ8KQRzvTKVqbNJ92QhQu"
                    + "kG46EWN+JvrBSuXjOEzq5XexKDQleSp8YFmjpgwVJAI6DNgdImg9IlvmmKPZbdpo"
                    + "dPxyBDvBaqFXf7G44bUOqkaCebe/T3Jf1lsQv/vAiA1q2K2I3QyAa6/MK+U0iQQy"
                    + "OsLsFABoRtjCl6RhdiwjvYg=";

    @Test
    @DisplayName("유효한 Base64 PKCS#8 개인키를 RSA PrivateKey로 파싱한다")
    void parsesValidPrivateKey() {
        RoomTokenProperties props = new RoomTokenProperties("live", "chat", VALID_PKCS8_BASE64, 90L);

        PrivateKey key = config.roomTokenPrivateKey(props);

        assertThat(key).isNotNull();
        assertThat(key.getAlgorithm()).isEqualTo("RSA");
    }

    @Test
    @DisplayName("잘못된 개인키는 기동 단계에서 IllegalStateException으로 실패시키고, 예외 메시지에 키 원문을 노출하지 않는다")
    void failsFastOnInvalidKey() {
        RoomTokenProperties props = new RoomTokenProperties("live", "chat", "not-a-valid-key", 90L);

        assertThatThrownBy(() -> config.roomTokenPrivateKey(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("룸 토큰 개인키")
                .hasMessageNotContaining("not-a-valid-key");
    }

    @Test
    @DisplayName("toString()은 개인키를 마스킹한다 — 로그로 RS256 키가 유출되지 않도록")
    void toStringMasksPrivateKey() {
        RoomTokenProperties props = new RoomTokenProperties("live", "chat", VALID_PKCS8_BASE64, 90L);

        String s = props.toString();

        assertThat(s).contains("privateKey=***");
        assertThat(s).doesNotContain(VALID_PKCS8_BASE64);
        // 비-민감 필드는 정상 노출(디버깅 가능)
        assertThat(s).contains("issuer=live").contains("audience=chat").contains("expirationSeconds=90");
    }

    // ------------------------------------------------------------------------
    // 서명 캐시 빈 — 이 세 칸이 없으면 @Bean 메서드의 분기와 가드 호출이 분모 0 이다.
    // 리뷰(SPR-173 R1 ㄱ-3)가 잡았다: 이전에는 RoomTokenCacheProperties.enabled() 가 false 를
    // 돌려주는 것만 단언해서, enabled 를 무시하고 항상 데코레이터를 끼워도 초록이었다.
    // ------------------------------------------------------------------------

    private static RoomTokenProperties issuance(long expirationSeconds) {
        return new RoomTokenProperties("live", "chat", VALID_PKCS8_BASE64, expirationSeconds);
    }

    @Test
    @DisplayName("켜져 있으면 서명 캐시를 @Primary 로 끼운다")
    void theEnabledCacheWrapsTheProvider() {
        RoomTokenProvider provider = mock(RoomTokenProvider.class);

        RoomTokenIssuer issuer = config.cachingRoomTokenIssuer(provider, mock(LiveMetrics.class),
                issuance(90L), new RoomTokenCacheProperties(true, 16, 30L));

        assertThat(issuer).isInstanceOf(CachingRoomTokenIssuer.class);
    }

    @Test
    @DisplayName("꺼져 있으면 원본을 그대로 돌려준다 — 호출 경로에 데코레이터가 0줄 남는다")
    void theDisabledCacheReturnsTheProviderItself() {
        RoomTokenProvider provider = mock(RoomTokenProvider.class);

        RoomTokenIssuer issuer = config.cachingRoomTokenIssuer(provider, mock(LiveMetrics.class),
                issuance(90L), new RoomTokenCacheProperties(false, 16, 30L));

        // isSameAs 여야 한다 — isInstanceOf 로는 "감싼 뒤 위임" 과 구분되지 않는다.
        assertThat(issuer).isSameAs(provider);
    }

    @Test
    @DisplayName("두 레코드에 걸친 TTL 불변식을 이 빈이 실제로 검사한다 — 호출을 지우면 이 칸이 깨진다")
    void theBeanInvokesTheCrossRecordGuard() {
        // 캐시 46s 는 발급 90s 의 절반을 넘는다. 어기는 조합이 여기서 걸려야 기동이 멈춘다.
        assertThatThrownBy(() -> config.cachingRoomTokenIssuer(
                mock(RoomTokenProvider.class), mock(LiveMetrics.class),
                issuance(90L), new RoomTokenCacheProperties(true, 16, 46L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(RoomTokenCacheProperties.TTL_MUST_LEAVE_HALF_THE_LIFE);
    }
}
