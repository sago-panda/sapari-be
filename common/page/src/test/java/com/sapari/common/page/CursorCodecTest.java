package com.sapari.common.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sapari.common.core.exception.CommonErrorCode;

class CursorCodecTest {

    /** encode 를 거치지 않고 임의 파트 수의 커서를 만든다(파트 수 검증 테스트용). */
    private static String rawCursor(String joined) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(joined.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void encode_decode_roundtrip_정렬키_id() {
        UUID id = UUID.randomUUID();
        String cursor = CursorCodec.encode("1500", id.toString());

        Cursor decoded = CursorCodec.decode(cursor);

        assertThat(decoded.sortKeyAsLong()).isEqualTo(1500L);
        assertThat(decoded.idAsUuid()).isEqualTo(id);
        assertThat(decoded.sortKey()).isEqualTo("1500");
    }

    @Test
    void decode_는_InvalidCursorException_을_INVALID_INPUT_으로_던진다() {
        assertThatExceptionOfType(InvalidCursorException.class)
                .isThrownBy(() -> CursorCodec.decode("!!!not-base64!!!"))
                .satisfies(ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT);
                    assertThat(ex.getErrorCode().getStatus()).isEqualTo(400);
                });
    }

    @Test
    void decode_null_빈값_상한초과는_InvalidCursorException() {
        assertThatThrownBy(() -> CursorCodec.decode(null)).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> CursorCodec.decode("")).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> CursorCodec.decode("a".repeat(513))).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void encode_part에_구분자가_있으면_IllegalStateException() {
        UUID id = UUID.randomUUID();

        // 정렬키에 구분자(문자열 필드 정렬 추정) → 서버 버그로 즉시 실패
        assertThatThrownBy(() -> CursorCodec.encode("삼성|노트북", id.toString()))
                .isInstanceOf(IllegalStateException.class);
        // id 에 구분자
        assertThatThrownBy(() -> CursorCodec.encode("1500", "a|b"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decode_2파트가_아니면_InvalidCursorException() {
        assertThatThrownBy(() -> CursorCodec.decode(rawCursor("single")))
                .isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> CursorCodec.decode(rawCursor("a|b|c")))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void sortKeyAsLong_파싱_실패는_InvalidCursorException() {
        Cursor cursor = CursorCodec.decode(CursorCodec.encode("notNumber", UUID.randomUUID().toString()));

        assertThatThrownBy(cursor::sortKeyAsLong).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void idAsUuid_파싱_실패는_InvalidCursorException() {
        Cursor cursor = CursorCodec.decode(CursorCodec.encode("1500", "not-a-uuid"));

        assertThatThrownBy(cursor::idAsUuid).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void 예외_메시지는_커서_원문을_담지_않는다() {
        String tampered = rawCursor("attacker|payload|extra");

        assertThatExceptionOfType(InvalidCursorException.class)
                .isThrownBy(() -> CursorCodec.decode(tampered))
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(tampered));
    }
}
