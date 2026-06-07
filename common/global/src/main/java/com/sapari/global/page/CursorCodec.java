package com.sapari.global.page;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 복합 커서 인코딩/디코딩. 정렬키 + id 를 URL-safe Base64 불투명 토큰으로 묶는다.
 *
 * <p>Base64 는 암호화가 아니라 decode 결과는 변조 가능한 신뢰 불가 입력이다. 도메인은 파트를
 * Long/UUID 로 파싱·검증하고, 파라미터 바인딩만 쓰며, 인가는 cursor 와 독립 적용한다(IDOR 방지).
 * 정렬키에 {@code '|'} 가 들어가면 깨지므로 숫자/UUID 정렬키를 전제한다.
 */
public final class CursorCodec {

    private static final String DELIMITER = "|";
    private static final int MAX_CURSOR_LENGTH = 512; // 거대 입력 디코딩 자원 소모(저강도 DoS) 방지

    private CursorCodec() {}

    /**
     * 정렬키 + id 등을 하나의 불투명 커서로 인코딩한다.
     */
    public static String encode(String... parts) {
        String joined = String.join(DELIMITER, parts);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(joined.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 커서를 파트 배열로 복원한다. null/빈값/상한초과/형식오류는 IllegalArgumentException.
     */
    public static String[] decode(String cursor) {
        if (cursor == null || cursor.isEmpty() || cursor.length() > MAX_CURSOR_LENGTH) {
            throw new IllegalArgumentException("잘못된 커서 형식입니다.");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            return new String(decoded, StandardCharsets.UTF_8).split("\\" + DELIMITER, -1);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("잘못된 커서 형식입니다.", e);
        }
    }
}
