package com.sapari.chat.domain.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("ProfanityFilter")
class ProfanityFilterTest {

    // 테스트용 사전 — 변형형(ㅅㅂ·시ㅂ)은 자모 합성 없이 리터럴 엔트리로 둔다(본 버전 계약).
    private static final Set<String> WORDS = Set.of("시발", "개새끼", "ㅅㅂ", "시ㅂ", "fuck");
    private static final Set<String> WHITELIST = Set.of("시발점");

    private final ProfanityFilter filter = new ProfanityFilter(WORDS, WHITELIST);

    @DisplayName("filter — 3-스텝 마스킹/보존")
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void filter(String tc, String input, String expected) {
        // when
        String result = filter.filter(input);

        // then
        assertThat(result).isEqualTo(expected);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                arguments("#1 욕설 없음 → 원문 유지", "안녕하세요", "안녕하세요"),
                arguments("#2 직접 욕설 1개 → 해당 span만 마스킹", "시발 안녕", "*** 안녕"),
                arguments("#3 직접 욕설 여러 개 → 각 span 마스킹", "시발 개새끼", "*** ***"),
                arguments("#4a 변형형 ㅅㅂ → 마스킹", "ㅅㅂ", "***"),
                arguments("#4b 변형형 시ㅂ → 마스킹", "시ㅂ", "***"),
                arguments("#5 욕설만 → ***", "시발", "***"),
                arguments("#6a null → 그대로", null, null),
                arguments("#6b 빈 문자열 → 그대로", "", ""),
                arguments("#7 정상어(욕설 부분문자열 아님) → 유지", "화살이 빠르다", "화살이 빠르다"),
                arguments("#8a 영어 대문자 Fuck → 마스킹(대소문자 무관)", "Fuck", "***"),
                arguments("#8b 영어 FUCK 문장 → 부분 마스킹", "FUCK 안녕", "*** 안녕"),
                arguments("#9 공백 우회 '시 발' → 2B 전체 ***", "시 발", "***"),
                arguments("#10 특수문자 우회 '시.발' → 2A 전체 ***", "시.발", "***"),
                arguments("#10b 숫자 우회 '시1발' → 2A 전체 ***", "시1발", "***"),
                arguments("#11 화이트리스트 '시발점이' → 유지", "시발점이 어디냐", "시발점이 어디냐"),
                arguments("#12 정상어 '출발 분석' → 유지", "출발 분석", "출발 분석"),
                arguments("#13 직접+공백우회 동시 → 우회 부분만 정밀 마스킹", "시발 시 발 좀", "*** *** 좀"),
                arguments("#14 경계 합성 오탐 '구매시 발송' → 미발화·유지", "구매시 발송", "구매시 발송"),
                arguments("#15 다음절 정상어 '시 발표' → 미발화·유지", "시 발표", "시 발표"),
                arguments("#16 원문에 욕설 있으면 2A 미발화 (인접 특수문자 '시발!') → 부분만", "시발! 안녕", "***! 안녕"),
                arguments("#17 혼합 우회 '시. 발' → 2A전처리+2B 전체 ***", "시. 발", "***"),
                arguments("#18 화이트리스트 2B 조인 '시 발점' → 유지", "시 발점", "시 발점"),
                arguments("#19 3토큰 음절 분산 '개 새 끼' → 2B(K=3) 전체 ***", "개 새 끼", "***"),
                arguments("#20 일부러 끼운 숫자 '시1발점' → 우회로 2A 마스킹(literal 화이트리스트)", "시1발점 어디냐", "*** 어디냐"),
                arguments("#21 2A 정밀 '시.발 안녕' → 우회 토큰만 마스킹", "시.발 안녕", "*** 안녕"),
                arguments("#22 2B 정밀 '시 발 안녕' → 우회 윈도우만 마스킹", "시 발 안녕", "*** 안녕"),
                arguments("#23 한 토큰에 깨끗한 욕설+우회 공존 '시발시1발' → 욕설 수 증가로 2A 발화·토큰 전체 ***", "시발시1발", "***"),
                arguments("#24 윈도우 앞 정상어 '안녕 시 발' → '안녕 ***'(대칭)", "안녕 시 발", "안녕 ***"),
                arguments("#25 로케일 길이변화 문자(İ U+0130)+욕설 → 크래시 없이 마스킹", "İfuck 안녕", "İ*** 안녕")
        );
    }

    @Test
    @DisplayName("사전에 null·공백이 섞여도 무시한다 — 사전은 DB에서 오고 빈 항목은 전 문장을 마스킹한다")
    void dictionaryWithDirtyEntries() {
        // given: 로더가 NULL 컬럼이나 공백 행을 그대로 넘길 수 있다
        Set<String> dirty = new HashSet<>();
        dirty.add("시발");
        dirty.add(null);
        dirty.add("   ");
        Set<String> dirtyWhite = new HashSet<>();
        dirtyWhite.add("시발점");
        dirtyWhite.add(null);
        dirtyWhite.add("");

        // when
        ProfanityFilter f = new ProfanityFilter(dirty, dirtyWhite);

        // then: 멀쩡한 문장이 통째로 마스킹되지 않고, 정상 사전 항목은 그대로 동작한다
        assertThat(f.filter("안녕하세요")).isEqualTo("안녕하세요");
        assertThat(f.filter("시발")).isEqualTo("***");
        assertThat(f.filter("시발점이 어디냐")).isEqualTo("시발점이 어디냐");
    }

    @Test
    @DisplayName("맞닿은 욕설 두 개는 하나의 마스킹으로 합쳐진다 — 별개로 두면 경계가 어긋난다")
    void adjacentMatchesAreMerged() {
        // given & when: 사이에 아무것도 없이 붙어 있으면 두 범위가 맞닿는다
        String result = filter.filter("시발개새끼");

        // then: 별표가 두 덩어리로 갈라지면 원문 길이·경계가 드러난다
        assertThat(result).isEqualTo("***");
    }

    @Test
    @DisplayName("겹치는 욕설(하나가 다른 하나 안에 있음)도 한 덩어리로 나온다")
    void overlappingMatchesAreMerged() {
        // given: "시발"과 그것을 포함하는 더 긴 항목이 같은 구간에서 걸린다
        ProfanityFilter nested = new ProfanityFilter(Set.of("시발", "시발놈"), Set.of());

        // when & then
        assertThat(nested.filter("시발놈")).isEqualTo("***");
    }
}
