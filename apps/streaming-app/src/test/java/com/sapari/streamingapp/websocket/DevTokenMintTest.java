package com.sapari.streamingapp.websocket;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Jwts;

/**
 * 로컬 수동 테스트용 발급기(자동 CI 제외 — @Disabled). 실행:
 *   ./gradlew :apps:streaming-app:test --tests "*DevTokenMintTest" -Dmint=on
 * 결과는 콘솔 + build/dev-token.txt 에 기록된다.
 *   1) public-key 를 application.yml의 chat.room-token.public-key 에 넣고
 *   2) bootRun 후 아래 ws URL로 접속하면 ROOM_INFO 수신, JSON 전송 시 ACK.
 *
 * 새 키쌍을 매번 생성하므로(공개키↔토큰 짝) 재실행하면 공개키도 다시 설정해야 한다. exp는 1시간(수동 여유).
 */
class DevTokenMintTest {

    // 접속 URL에 쓸 고정 값(원하면 변경)
    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String ROLE = "SELLER";   // SELLER+owner=true면 NOTICE·방주인 PII까지 테스트 가능
    private static final boolean OWNER = true;

    @Test
    void mint() throws Exception {
        // 평소엔 assumption으로 skip(green), -Dmint=on 일 때만 실제 발급
        org.junit.jupiter.api.Assumptions.assumeTrue("on".equals(System.getProperty("mint")));

        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();

        String publicKeyB64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        String token = Jwts.builder()
                .issuer("live").audience().add("chat").and()
                .subject(USER_ID.toString())
                .claim("room", ROOM_ID.toString())
                .claim("role", ROLE)
                .claim("owner", OWNER)
                .claim("nickname", "테스트셀러")
                .claim("email", "seller@example.com")
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))   // 1시간
                .signWith(kp.getPrivate())
                .compact();

        String out = """
                === DEV TOKEN MINT ===
                # 1) application.yml 에 설정:
                chat:
                  room-token:
                    public-key: %s

                # 2) bootRun 후 접속(websocat 예):
                websocat "ws://localhost:8080/ws/chat?roomId=%s&token=%s"

                # 3) 접속 후 전송 예:
                {"type":"NOTICE","content":"테스트 공지","clientMsgId":"c1"}
                """.formatted(publicKeyB64, ROOM_ID, token);

        System.out.println(out);
        Path file = Path.of("build", "dev-token.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, out);
        System.out.println("→ build/dev-token.txt 에도 기록됨");
    }
}
