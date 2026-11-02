package com.sapari.chat.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.domain.rule.ProfanityFilter;

/**
 * 사전이 <b>실제로 마스킹을 켜는지</b>, 그리고 꺼진 상태로 뜰 수 없는지 본다.
 *
 * <p>이 기능은 사전이 비어 있는 동안 <b>조용히 꺼진 채로</b> 운영 경로에 서 있었다
 * ({@code displayMessage == originalMessage}). 그래서 여기서 재는 것은 단어 수가 아니라
 * "마스킹이 걸린다" 와 "안 걸린 채로는 못 뜬다" 두 가지다.
 */
@DisplayName("욕설 사전 — 마스킹을 켜고, 꺼진 채로는 뜨지 못한다")
class ProfanityDictionaryTest {

    private static ProfanityFilter filterFromDictionary() {
        ProfanityDictionary dictionary = ProfanityDictionary.load(null, null);
        return new ProfanityFilter(dictionary.words(), dictionary.whitelist());
    }

    @Test
    @DisplayName("⭐ 기본 사전만으로 마스킹이 걸린다 — 비어 있던 동안 이 경로가 통째로 꺼져 있었다")
    void theBundledDictionaryActuallyMasks() {
        ProfanityFilter filter = filterFromDictionary();

        assertThat(filter.filter("시발"))
                .as("기본 사전으로 아무것도 가려지지 않는다 — 리소스가 비었거나 안 읽혔다")
                .isNotEqualTo("시발");
        assertThat(filter.filter("안녕하세요 반갑습니다"))
                .as("정상 문장이 마스킹됐다 — 사전에 지나치게 짧은 단어가 섞였다")
                .isEqualTo("안녕하세요 반갑습니다");
    }

    @Test
    @DisplayName("⭐ 화이트리스트가 정상어를 보호한다 — 없으면 '시발점' 이 마스킹된다")
    void theWhitelistProtectsOrdinaryWords() {
        ProfanityDictionary dictionary = ProfanityDictionary.load(null, null);
        ProfanityFilter withWhitelist =
                new ProfanityFilter(dictionary.words(), dictionary.whitelist());
        ProfanityFilter withoutWhitelist =
                new ProfanityFilter(dictionary.words(), java.util.Set.of());

        assertThat(withWhitelist.filter("시발점")).isEqualTo("시발점");
        assertThat(withoutWhitelist.filter("시발점"))
                .as("화이트리스트가 없는데도 보존됐다 — 그러면 이 테스트가 보호를 재지 못한다")
                .isNotEqualTo("시발점");
    }

    /**
     * ⭐ 화이트리스트 선별이 우회로를 만들지 않는다.
     *
     * <p>이 저장소의 화이트리스트 의미는 "토큰이 항목을 <b>포함</b>하면 그 토큰 전체를 보존" 이다
     * ({@code ProfanityFilter.isWhitelisted} → {@code containsAny}). 그래서 안에 욕설이 없는 항목은
     * 보호할 대상이 없고 <b>우회로만</b> 만든다. 원본 목록(496)을 그대로 넣었을 때의 실측이 그것이다:
     * {@code 너가시발 → 너가시발}, {@code 카카오시발 → 카카오시발}, {@code 0개시발 → 0개시발}.
     * 선별 기준("항목 안에 욕설이 있을 때만 유지")이 그 셋을 닫았고, 이 테스트가 그 사실을 고정한다.
     */
    @Test
    @DisplayName("⭐ 화이트리스트가 우회로를 만들지 않는다 — 흔한 조각이 섞이면 토큰 전체가 통과한다")
    void theWhitelistDoesNotOpenBypasses() {
        ProfanityFilter filter = filterFromDictionary();

        assertThat(filter.filter("너가시발"))
                .as("'너가' 같은 조각이 화이트리스트에 있으면 이 토큰이 통째로 통과한다")
                .isNotEqualTo("너가시발");
        assertThat(filter.filter("카카오시발")).isNotEqualTo("카카오시발");
        assertThat(filter.filter("0개시발")).isNotEqualTo("0개시발");
    }

    @Test
    @DisplayName("설정 단어는 사전에 더해진다 — 교체면 설정 한 줄이 사전을 날린다")
    void configuredWordsAreAddedNotReplacing() {
        ProfanityDictionary dictionary = ProfanityDictionary.load("김치찌개", "김치찌개맛집");

        assertThat(dictionary.words())
                .as("설정 단어가 들어오지 않았다").contains("김치찌개")
                .as("설정이 기본 사전을 교체했다").hasSizeGreaterThan(100);
        assertThat(dictionary.whitelist()).contains("김치찌개맛집").hasSizeGreaterThan(100);
    }

    @Test
    @DisplayName("빈 설정은 무시한다 — null·공백이 사전을 흔들지 않는다")
    void blankConfigIsIgnored() {
        ProfanityDictionary base = ProfanityDictionary.load(null, null);
        ProfanityDictionary blank = ProfanityDictionary.load("  ", ", ,");

        assertThat(blank.words()).isEqualTo(base.words());
        assertThat(blank.whitelist()).isEqualTo(base.whitelist());
    }

    /**
     * ⭐ 리소스가 없거나 비면 <b>부팅을 실패시킨다.</b>
     *
     * <p>이 단언이 지키는 것은 "조용히 빈 사전으로 떠서 마스킹이 꺼지는 일은 없다" 다. 그 상태가
     * 실제로 운영 경로에 서 있었고, 남은 것은 부팅 WARN 한 줄이었다.
     */
    @Test
    @DisplayName("⭐ 리소스가 없으면 부팅을 실패시킨다 — 조용한 pass-through 보다 시끄러운 실패가 낫다")
    void aMissingResourceFailsBoot() {
        assertThatThrownBy(() -> ProfanityDictionary.load("/chat/없는-파일.txt", "/chat/profanity-whitelist.txt", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("욕설 사전 리소스가 없다")
                .as("조치가 아니라 사실만 적혀 있다 — 당직자가 무엇을 봐야 할지 알 수 없다")
                .hasMessageContaining("마스킹이 조용히 꺼지므로");
    }

    /**
     * ⭐ 읽히긴 했으나 단어가 0개여도 실패다.
     *
     * <p>처음에는 같은 디렉터리의 {@code LICENSE-korcen.txt} 를 "빈 사전" 대용으로 썼는데 통과했다 —
     * 그 파일은 {@code #} 주석이 아니라 산문이라 로더가 <b>라이선스 문장을 단어로 읽는다.</b> 그래서
     * 주석만 든 테스트 리소스를 따로 뒀다. 운영에서 이 모양이 되는 경로는 "단어를 지우고 설명만
     * 남기는" 편집이다.
     */
    @Test
    @DisplayName("⭐ 사전이 주석·빈 줄뿐이면 그것도 실패다 — 단어 0개는 기능 꺼짐과 같다")
    void aCommentsOnlyResourceFailsBoot() {
        assertThatThrownBy(() -> ProfanityDictionary.load(
                "/chat/comments-only-dictionary.txt", "/chat/profanity-whitelist.txt", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("비어 있다");
    }

    @Test
    @DisplayName("사전은 불변 사본으로 나온다 — 호출자가 비울 수 있으면 마스킹이 조용히 꺼진다")
    void theDictionaryIsHandedOutImmutable() {
        ProfanityDictionary dictionary = ProfanityDictionary.load(null, null);

        assertThatThrownBy(() -> dictionary.words().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> dictionary.whitelist().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
