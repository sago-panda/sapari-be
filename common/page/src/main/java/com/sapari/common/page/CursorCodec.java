package com.sapari.common.page;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 단일복합 커서 인코딩/디코딩. 정렬키 + 타이브레이커(id) **2-파트 고정**을 URL-safe Base64 불투명 토큰으로 묶는다.
 *
 * <p>Base64 는 암호화가 아니라 decode 결과는 변조 가능한 신뢰 불가 입력이다. {@link Cursor} 의 타입 접근자가
 * Long/UUID 파싱·검증을 흡수하고, 도메인은 파라미터 바인딩만 쓰며, 인가는 cursor 와 독립 적용한다(IDOR 방지).
 * 정렬키에 {@code '|'} 가 들어가면 깨지므로 숫자/UUID 정렬키를 전제한다.
 *
 * <p>여러 정렬키가 필요한 다중복합 keyset 은 의도적으로 지원하지 않는다 — 정렬값 1개 + id 타이브레이커로 전순서가
 * 만들어지는 케이스(최신순/인기순/판매량순)만 다룬다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CursorCodec {

    private static final String DELIMITER = "|";
    private static final int PARTS = 2; // 정렬키 + id
    private static final int MAX_CURSOR_LENGTH = 512; // 거대 입력 디코딩 자원 소모(저강도 DoS) 방지

    /**
     * 정렬키 + id 를 하나의 불투명 커서로 인코딩한다.
     */
    public static String encode(String sortKey, String id) {
        // 구분자가 part 에 섞이면 decode 가 잘못 쪼갠다. 숫자/UUID 정렬키 전제(=구분자 없음)를 코드로 강제.
        // 커서는 서버가 만드는 값이라, 위반은 클라 입력 오류가 아니라 서버 버그(문자열 필드 정렬 추정) → IllegalStateException.
        if (sortKey.contains(DELIMITER) || id.contains(DELIMITER)) {
            throw new IllegalStateException(
                    "커서 part 에 구분자('" + DELIMITER + "') 포함: 숫자/UUID 정렬키 전제 위반(문자열 필드 정렬 추정).");
        }
        String joined = String.join(DELIMITER, sortKey, id);
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(joined.getBytes(StandardCharsets.UTF_8));

        if (encoded.length() > MAX_CURSOR_LENGTH) {
            throw new IllegalStateException("커서 길이 상한 초과: 정렬키가 숫자/UUID 전제를 위반했습니다.");
        }

        return encoded;
    }

    /**
     * 커서를 정렬키 + id 로 복원한다. 커서는 클라이언트가 보낸 신뢰 불가 입력이므로,
     * null/빈값/상한초과/Base64 오류/파트 수(≠2) 불일치는 모두 {@link InvalidCursorException}(400)으로 던진다
     * (raw IllegalArgumentException 이 새어 500 으로 매핑되지 않게).
     *
     * @param cursor 클라이언트가 보낸 불투명 커서
     */
    public static Cursor decode(String cursor) {
        if (cursor == null || cursor.isEmpty() || cursor.length() > MAX_CURSOR_LENGTH) {
            throw new InvalidCursorException("커서 형식 오류: null/빈값/상한초과");
        }
        String[] parts;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            parts = new String(decoded, StandardCharsets.UTF_8).split("\\" + DELIMITER, -1);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("커서 Base64 디코딩 실패", e);
        }
        if (parts.length != PARTS) {
            throw new InvalidCursorException(
                    "커서 파트 수 불일치: expected=" + PARTS + ", actual=" + parts.length);
        }
        return new Cursor(parts[0], parts[1]);
    }
}
