package com.sapari.chat.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.support.LiveSchema;

/**
 * 사전 항목 길이를 정하는 두 축이 서로를 넘지 않는가.
 *
 * <p><b>왜 이 테스트가 있는가.</b> 항목 길이에는 축이 둘 있고 둘 다 거부를 만든다 —
 * {@link ProfanityDictionary#MAX_WORD_LENGTH}(지연 예산)과 정본 열
 * {@code chat_filter_word.word} 의 {@code varchar(n)} 이다. 추가 경로는 <b>문을 먼저</b>
 * 통과시키고 그 뒤에 INSERT 한다({@code ManageFilterWordsService}: {@code guardRequested} →
 * {@code add}).
 *
 * <p>그래서 예산이 열 폭보다 커지면 문이 통과시킨 항목이 INSERT 에서 제약 위반으로 떨어진다 —
 * 관리자에게는 400(사유 있는 거부)이 아니라 500 이 가고, 거부 사유가 응답도 로그 한 줄도 아닌
 * 드라이버 예외가 된다. 반대로 열이 예산 아래로 <b>좁아지면</b> 같은 500 이 난다.
 *
 * <p><b>단위.</b> 문은 {@code word.length()} — 즉 <b>UTF-16 코드 유닛</b>이다(코드포인트가
 * 아니다. 같은 클래스가 {@code U+1D160} 하나가 UTF-16 에서 2 유닛이라고 적어 둔 그 구분이다).
 * Postgres {@code varchar(n)} 은 <b>문자 수</b>(=코드포인트)를 센다. 유닛 ≥ 코드포인트이므로
 * {@code 유닛 상한 ≤ 열 폭} 이면 코드포인트도 열 폭 이하다 — <b>비교 방향이 안전한 쪽</b>이라
 * 단위가 달라도 이 부등식은 성립한다.
 */
class DictionaryLengthAxisTest {

    /** {@code word varchar(64)} 꼴 — CREATE TABLE 안의 열 정의. */
    private static final Pattern CREATE_COLUMN =
            Pattern.compile("\\bword\\s+varchar\\((\\d+)\\)", Pattern.CASE_INSENSITIVE);

    /**
     * {@code ALTER COLUMN word TYPE varchar(128)} / {@code SET DATA TYPE} 꼴.
     *
     * <p>이 패턴이 따로 있는 이유: {@code word} 와 {@code varchar} 사이에 {@code TYPE} 이
     * 끼어 위 패턴이 <b>매칭되지 않는다.</b> 처음엔 위 패턴 하나로 두고 javadoc 에 "나중 버전이
     * 열을 ALTER 하면 그 값이 운영의 폭" 이라고 적었는데, 그 문장이 가리키는 형태를 정작
     * 정규식이 못 봤다 — 그리고 <b>안 보이는 방향이 해로운 쪽</b>이었다(열이 좁아지는 변경이
     * 통과한다).
     */
    private static final Pattern ALTER_COLUMN = Pattern.compile(
            "\\balter\\s+column\\s+word\\s+(?:set\\s+data\\s+)?type\\s+varchar\\((\\d+)\\)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 정본 테이블의 {@code CREATE TABLE ... (...)} 블록 — 열 이름이 겹치는 다른 테이블을 뺀다.
     *
     * <p>오늘 {@code word} 열은 저장소 전수 1건이라 도달 0 이지만, 같은 이름의 열이 하나
     * 생기면 "마지막 매칭이 이긴다" 루프가 <b>틀린 테이블의 폭</b>을 읽는다.
     */
    private static final Pattern FILTER_WORD_TABLE = Pattern.compile(
            "create\\s+table[^;]*?chat_filter_word\\s*\\(([^;]*?)\\)\\s*;",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Test
    @DisplayName("⭐ 사전 문의 길이 상한이 정본 열 폭을 넘지 않는다")
    void theDoorBudgetFitsTheCanonicalColumn() throws IOException {
        int columnWidth = wordColumnWidth(readMigrations());

        assertThat(ProfanityDictionary.MAX_WORD_LENGTH)
                .as("상한이 열 폭을 넘으면 문을 지난 항목이 INSERT 에서 떨어져 500 이 된다")
                .isLessThanOrEqualTo(columnWidth);
    }

    /**
     * ⭐ <b>{@code ALTER} 로 열이 좁아지는 변경을 본다 — 이 덫의 유일한 해로운 방향이다.</b>
     *
     * <p>실제 마이그레이션에 {@code ALTER TABLE} 이 <b>0건</b>이라 위 칸으로는 이 분기의
     * 분모가 0 이다. 합성 DDL 로 벡터를 만든다 — 그러지 않으면 "나중 버전이 ALTER 하면" 이라고
     * 적은 문장을 어느 칸도 받치지 않는다.
     */
    @Test
    @DisplayName("⭐ ALTER 로 좁아진 열 폭을 읽는다 — 확대도 축소도")
    void anAlteredColumnWidthWins() {
        String create = """
                CREATE TABLE live_schema.chat_filter_word (
                    kind varchar(16) NOT NULL,
                    word varchar(64) NOT NULL
                );
                """;

        assertThat(wordColumnWidth(List.of(create)))
                .as("ALTER 가 없으면 CREATE 의 폭")
                .isEqualTo(64);
        assertThat(wordColumnWidth(List.of(create,
                "ALTER TABLE live_schema.chat_filter_word ALTER COLUMN word TYPE varchar(128);")))
                .isEqualTo(128);
        assertThat(wordColumnWidth(List.of(create,
                "ALTER TABLE live_schema.chat_filter_word ALTER COLUMN word SET DATA TYPE varchar(8);")))
                .as("축소가 안 보이면 운영은 16유닛 항목을 거부하는데 테스트는 64 를 읽고 통과한다")
                .isEqualTo(8);
    }

    /**
     * ⭐ <b>다른 테이블의 같은 이름 열을 읽지 않는다.</b>
     *
     * <p>오늘 {@code word} 열은 전수 1건이라 도달 0 이고, 이 칸은 그 전수가 깨지는 날을 위한
     * 덫이다 — 범위를 테이블 블록으로 좁힌 그 변경의 분모다.
     */
    @Test
    @DisplayName("⭐ 같은 이름의 다른 테이블 열을 읽지 않는다")
    void aSameNamedColumnInAnotherTableIsIgnored() {
        List<String> ddl = List.of("""
                CREATE TABLE live_schema.chat_filter_word (
                    word varchar(64) NOT NULL
                );
                CREATE TABLE live_schema.search_keyword (
                    word varchar(255) NOT NULL
                );
                """);

        assertThat(wordColumnWidth(ddl))
                .as("범위를 좁히지 않으면 255 를 읽고 예산 검사가 느슨해진다")
                .isEqualTo(64);
    }

    private static List<String> readMigrations() throws IOException {
        List<String> out = new ArrayList<>();
        for (Path migration : LiveSchema.migrationFiles()) {
            out.add(Files.readString(migration));
        }
        return out;
    }

    /**
     * 정본 DDL 에서 {@code chat_filter_word.word} 의 폭을 읽는다.
     *
     * <p>마이그레이션을 <b>Flyway 순서대로</b> 훑어 마지막으로 선언된 폭을 쓴다 — 나중 버전이
     * 열을 바꾸면 그 값이 운영의 폭이기 때문이다({@link LiveSchema} 가 버전을 수로 비교한다).
     */
    private static int wordColumnWidth(List<String> migrations) {
        int width = -1;
        for (String ddl : migrations) {
            Matcher table = FILTER_WORD_TABLE.matcher(ddl);
            while (table.find()) {
                Matcher column = CREATE_COLUMN.matcher(table.group(1));
                while (column.find()) {
                    width = Integer.parseInt(column.group(1));
                }
            }
            Matcher altered = ALTER_COLUMN.matcher(ddl);
            while (altered.find()) {
                width = Integer.parseInt(altered.group(1));
            }
        }
        assertThat(width)
                .as("정본 DDL 에서 chat_filter_word.word 의 폭을 찾지 못했다 — 분모가 0 이다")
                .isGreaterThan(0);
        return width;
    }
}
