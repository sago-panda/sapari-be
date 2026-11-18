package com.sapari.liveapp.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 주체({@code sub})를 샤드 인덱스로 접는 키 혼합(HmacSHA256) — 프로세스 로컬 랜덤 키라 샤드를
 * <b>조준할 수 없다.</b>
 *
 * <p>왜 단순 {@code hashCode} 나 문자열 솔트 연접이 아닌지는 {@code EnterRateLimiter.shardKey}
 * javadoc 이 적는다(공개 목록 응답의 {@code sellerId} 로 샤드를 미리 계산할 수 있었다 — SPR-179 실측).
 * 이 클래스는 그 방식을 채팅 조회 상한({@link ChatReadRateLimiter})이 쓰도록 뽑은 것이다.
 *
 * <p>⚠️ {@code EnterRateLimiter} 는 같은 코드를 아직 자기 안에 둔다. 그쪽 javadoc 을 다른 문서가
 * 인용하고 있어서 이 티켓(SPR-203)에서는 옮기지 않았다.
 */
final class SubjectShardKey {

    private final byte[] key = newKey();

    /** {@code Mac} 은 상태를 들고 있어 스레드 안전하지 않다 — 스레드마다 하나 둔다(호출자는 유계 서블릿 풀). */
    private final ThreadLocal<Mac> mac = ThreadLocal.withInitial(this::newMac);

    /** 한 번 만들어 본다 — 실패를 첫 요청이 아니라 빈 생성(기동)에서 드러내기 위해서다. */
    SubjectShardKey() {
        newMac();
    }

    /** {@code subject} 를 {@code [0, shards)} 로 접는다. */
    int indexFor(String subject, int shards) {
        byte[] digest = mac.get().doFinal(subject.getBytes(StandardCharsets.UTF_8));
        // MAC 출력은 균등하므로 앞 4바이트를 int 로 접는다. 부호 있는 값이라 floorMod 로 접는다.
        int folded = ((digest[0] & 0xff) << 24) | ((digest[1] & 0xff) << 16)
                | ((digest[2] & 0xff) << 8) | (digest[3] & 0xff);
        return Math.floorMod(folded, shards);
    }

    private static byte[] newKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /** 없으면 던진다 — 조용히 약한 해시로 떨어지면 조준 공격이 그대로 돌아온다. */
    private Mac newMac() {
        try {
            Mac instance = Mac.getInstance("HmacSHA256");
            instance.init(new SecretKeySpec(key, "HmacSHA256"));
            return instance;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "HmacSHA256 을 쓸 수 없다 — 샤드 조준 방어가 성립하지 않으므로 기동을 막는다", e);
        }
    }
}
