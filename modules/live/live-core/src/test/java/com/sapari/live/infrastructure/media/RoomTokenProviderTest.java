package com.sapari.live.infrastructure.media;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.RoomTokenClaims;
import com.sapari.live.infrastructure.config.RoomTokenProperties;

class RoomTokenProviderTest {

    // dev 로컬 키쌍 (RoomTokenConfigTest와 동일 개인키 + 대응 공개키)
    private static final String PRIVATE_KEY_B64 =
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

    private static final String PUBLIC_KEY_B64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAkzLHFl8vsyu7yWmNmmwS"
                    + "zObmWTP6zWv0KZ1IUvKeVJxv5ibhTUWKL5f+VZNJ+9Q68f0JI0phD8DrdoQxtSVc"
                    + "zvyKYF2YiWMozCaDFtP5RgMTuM4ePzxFxOKQfHXWuut/CL8ouPKOKkSCS9Gq+Tig"
                    + "Ibvp4pRVN2ra1SB6Xfcb0FLovXhNWvMpx7j9CCOzLYodpn85udl3s9k7Fk5XHKGg"
                    + "SvuqmZDW8YY8SG28jr8Rsjt6gZoGAVE2EQkRbt1oy5wVPjouYNJlUWngzgJPI6r9"
                    + "h/1ux3rY2MaZBAL31RPeIz6RfxhpbLakb9tY33T3CA8iXaZbAH0fGjSu3w1Falr"
                    + "VzQIDAQAB";

    private static final Instant FIXED_NOW = Instant.parse("2026-07-03T00:00:00Z");

    private RoomTokenProvider provider;
    private PublicKey publicKey;

    @BeforeEach
    void setup() throws Exception {
        RoomTokenProperties props = new RoomTokenProperties("live", "chat", PRIVATE_KEY_B64, 90L);

        PrivateKey privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(PRIVATE_KEY_B64)));
        publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY_B64)));

        TimeProvider timeProvider = new TimeProvider(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
        provider = new RoomTokenProvider(props, privateKey, timeProvider);
    }

    private Claims verify(String token) {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(publicKey)
                .requireIssuer("live")
                .requireAudience("chat")
                .clock(() -> Date.from(FIXED_NOW))
                .build()
                .parseSignedClaims(token);
        return jws.getPayload();
    }

    @Test
    @DisplayName("회원 룸 토큰: 공개키로 검증되고 모든 claim(owner·nickname·email 포함)이 담긴다")
    void issuesMemberToken() {
        UUID userId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        RoomTokenClaims claims = new RoomTokenClaims(userId, roomId, "SELLER", true, "판매자닉", "seller@sapari.com");

        String token = provider.issue(claims);
        Claims c = verify(token);

        assertThat(c.getSubject()).isEqualTo(userId.toString());
        assertThat(c.get("room", String.class)).isEqualTo(roomId.toString());
        assertThat(c.get("role", String.class)).isEqualTo("SELLER");
        assertThat(c.get("owner", Boolean.class)).isTrue();
        assertThat(c.get("nickname", String.class)).isEqualTo("판매자닉");
        assertThat(c.get("email", String.class)).isEqualTo("seller@sapari.com");
        assertThat(c.getExpiration()).isEqualTo(Date.from(FIXED_NOW.plusSeconds(90)));
    }

    @Test
    @DisplayName("게스트 룸 토큰: nickname/email claim이 생략된다(부재)")
    void issuesGuestToken_omitsNicknameAndEmail() {
        RoomTokenClaims claims = new RoomTokenClaims(UUID.randomUUID(), UUID.randomUUID(), "GUEST", false, null, null);

        String token = provider.issue(claims);
        Claims c = verify(token);

        assertThat(c.get("role", String.class)).isEqualTo("GUEST");
        assertThat(c.get("owner", Boolean.class)).isFalse();
        assertThat(c.containsKey("nickname")).isFalse();
        assertThat(c.containsKey("email")).isFalse();
    }

    @Test
    @DisplayName("다른 공개키로는 검증에 실패한다(RS256 비대칭 서명 확인)")
    void tokenFailsVerificationWithWrongKey() throws Exception {
        RoomTokenClaims claims = new RoomTokenClaims(UUID.randomUUID(), UUID.randomUUID(), "BUYER", false, "구매자", "buyer@sapari.com");
        String token = provider.issue(claims);

        // 무관한 새 RSA 공개키
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        PublicKey otherKey = kpg.generateKeyPair().getPublic();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                Jwts.parser().verifyWith(otherKey).build().parseSignedClaims(token)
        ).isInstanceOf(io.jsonwebtoken.security.SignatureException.class);
    }
}
