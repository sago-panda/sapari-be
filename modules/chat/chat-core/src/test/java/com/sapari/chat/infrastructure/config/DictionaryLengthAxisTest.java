package com.sapari.chat.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.support.LiveSchema;

/**
 * 사전 항목 길이를 정하는 두 축이 서로를 넘지 않는가.
 *
 * <p><b>왜 이 테스트가 있는가.</b> 항목 길이에는 축이 둘 있고 둘 다 거부를 만든다 —
 * {@link ProfanityDictionary#MAX_WORD_LENGTH}(지연 예산, 코드포인트)과 정본 열
 * {@code chat_filter_word.word}의 {@code varchar(n)}이다. 추가 경로는 <b>문을 먼저</b> 통과시키고
 * 그 뒤에 INSERT 한다({@code ManageFilterWordsService}: {@code guardRequested} → {@code add}).
 *
 * <p>그래서 예산이 열 폭보다 커지면 문이 통과시킨 항목이 INSERT 에서 제약 위반으로 떨어진다 —
 * 관리자에게는 400(사유 있는 거부)이 아니라 500 이 가고, 거부 사유가 응답도 로그 한 줄도 아닌
 * 드라이버 예외가 된다. 지금은 16 ≤ 64 라 <b>도달하지 않는다</b>; 이 테스트가 재는 것은 그 부등식
 * 자체이고, 예산을 올리는 커밋이 열도 같이 올려야 한다는 것을 그 커밋에서 알려 준다.
 *
 * <p>단위가 양쪽 다 <b>코드포인트</b>라 비교가 성립한다 — 문은 코드포인트로 세고
 * ({@code ProfanityDictionary} 의 절단도 같은 단위), Postgres {@code varchar(n)} 도 바이트가
 * 아니라 문자 수를 센다.
 */
class DictionaryLengthAxisTest {

    /** {@code word       varchar(64) NOT NULL} 꼴에서 n 을 집는다. */
    private static final Pattern WORD_COLUMN =
            Pattern.compile("\\bword\\s+varchar\\((\\d+)\\)", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("⭐ 사전 문의 K 예산이 정본 열 폭을 넘지 않는다")
    void theDoorBudgetFitsTheCanonicalColumn() throws IOException {
        int columnWidth = wordColumnWidth();

        assertThat(ProfanityDictionary.MAX_WORD_LENGTH)
                .as("예산이 열 폭을 넘으면 문을 지난 항목이 INSERT 에서 떨어져 500 이 된다")
                .isLessThanOrEqualTo(columnWidth);
    }

    /**
     * 정본 DDL 에서 {@code chat_filter_word.word} 의 폭을 읽는다.
     *
     * <p>마이그레이션 전체를 순서대로 훑어 <b>마지막</b> 정의를 쓴다 — 나중 버전이 열을
     * {@code ALTER} 하면 그 값이 운영의 폭이고, {@code V1} 만 읽으면 그 변경이 이 테스트에
     * 안 보인다({@link LiveSchema} 가 세 테스트에서 고친 바로 그 모양이다).
     */
    private static int wordColumnWidth() throws IOException {
        int width = -1;
        for (Path migration : LiveSchema.migrationFiles()) {
            Matcher matcher = WORD_COLUMN.matcher(Files.readString(migration));
            while (matcher.find()) {
                width = Integer.parseInt(matcher.group(1));
            }
        }
        assertThat(width)
                .as("정본 DDL 에서 word 열의 varchar(n) 을 찾지 못했다 — 이 테스트의 분모가 0 이다")
                .isGreaterThan(0);
        return width;
    }
}
