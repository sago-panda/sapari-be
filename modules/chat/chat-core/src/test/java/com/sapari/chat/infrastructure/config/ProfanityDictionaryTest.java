package com.sapari.chat.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sapari.chat.domain.rule.ProfanityFilter;

/**
 * 사전이 <b>실제로 마스킹을 켜는지</b>, 그리고 <b>평범한 한국어를 가리지 않는지</b>를 본다.
 *
 * <p>이 기능은 두 방향으로 틀릴 수 있고 둘 다 실제로 밟았다. 사전이 비어 있는 동안에는 조용히
 * 꺼진 채로 운영 경로에 서 있었고({@code displayMessage == originalMessage}), 그 다음 사전을
 * 채웠을 때는 가장 흔한 표기({@code 씨발})가 안 걸리면서 동시에 평범한 말({@code 하루 세끼},
 * {@code 먹어보지})이 가려졌다. 그래서 여기서 재는 것은 단어 수가 아니라 그 두 방향이다.
 */
@DisplayName("욕설 사전 — 흔한 욕설을 잡고, 평범한 한국어를 가리지 않는다")
class ProfanityDictionaryTest {

    private static final String WHITELIST = "/chat/profanity-whitelist.txt";

    private static ProfanityFilter filterFromDictionary() {
        ProfanityDictionary dictionary = ProfanityDictionary.load(null, null);
        return new ProfanityFilter(dictionary.words(), dictionary.whitelist());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "시발", "씨발", "씨팔", "ㅅㅂ", "ㅆㅂ", "존나", "졸라", "좆", "썅",
            "개새끼", "병신", "븅신", "지랄", "염병", "니미", "애미",
            "미친놈", "미친년", "창녀", "강간", "쌍놈", "씹창", "s1bal"})
    @DisplayName("흔한 표기가 전부 걸린다 — 변형만 든 사전은 기본 표기를 통째로 흘려보냈다")
    void commonProfanityIsMasked(String word) {
        assertThat(filterFromDictionary().filter(word))
                .as("'%s' 이(가) 그대로 나간다 — 사전에 기본 표기가 없다", word)
                .isNotEqualTo(word);
    }

    /**
     * 평범한 한국어가 가려지지 않는다.
     *
     * <p>이 단언들이 지키는 것은 "판매자가 상품을 설명하다가 검열되지 않는다" 다. 브로드캐스트는
     * 되돌릴 수 없고 가려진 본문이 그대로 영속되므로, 라이브 커머스에서는 이쪽 손해가 미탐보다
     * 크다. 아래 목록은 전부 실제로 가려졌던 문자열이다 — 저장소 한글 말뭉치 전수와 표본 측정에서
     * 나왔고, 사전에서 {@code 새끼 · 세끼 · 호로 · 개떡 · 유방 · 유두 · 고환 · 후장} 을 빼면서
     * 닫혔다.
     *
     * <p>뒤쪽 넷은 <b>출처 시드가 미리 이름을 적어 준 것</b>이다 — 그 파일 머리글의 "보류 —
     * 톤 정책 결정 필요" 목록이다. 첫 측정이 이것들을 놓친 이유가 그 목록을 벡터에 넣지
     * 않았기 때문이라, 이제 테스트가 목록 쪽을 들고 있는다. 사전에 2글자 항목을 더할 때는
     * 이 테스트부터 돌린다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "먹어보지", "입어보지", "해보지", "보지만",
            "하루 세끼 먹어요", "새끼 고양이 분양", "강아지 새끼 몇 마리예요",
            "이거 매운지 궁금", "주문번호로 확인해주세요", "암호로 잠겨 있어요",
            "쑥개떡 판매합니다", "성교육 자료", "구매시발송 예정",
            "전자지갑 결제", "부자지간", "새끼손가락",
            "시발점", "시발역", "졸라매고", "공개세미나",
            "유방암 검진", "유방 확대 상담", "유두 보호기", "수유 유두크림",
            "고환 검사", "한우 고환 손질", "후장 시세", "후장 마감 기준"})
    @DisplayName("평범한 한국어는 그대로 나간다 — 판매자 설명이 검열되면 되돌릴 수 없다")
    void ordinaryKoreanIsNotMasked(String sentence) {
        assertThat(filterFromDictionary().filter(sentence))
                .as("'%s' 이(가) 가려졌다 — 짧은 항목이 한국어 형태소와 겹친다", sentence)
                .isEqualTo(sentence);
    }

    @Test
    @DisplayName("화이트리스트가 정상어를 보호한다 — 없으면 '시발점' 이 마스킹된다")
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
     * 화이트리스트가 우회로를 만들지 않는다.
     *
     * <p>이 저장소의 화이트리스트 의미는 "토큰이 항목을 <b>포함</b>하면 그 토큰 전체를 보존" 이다
     * ({@code ProfanityFilter.isWhitelisted} → {@code containsAny}). 그래서 항목을 하나 잘못 넣으면
     * 그 조각을 붙인 토큰이 통째로 통과한다. 실제로 두 번 밟았다 — 맨 욕설에 숫자를 붙인
     * {@code 8시발}, 그리고 사전에 없는 욕설을 보호하던 {@code 입어보지} 류 24건이다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"너가시발", "카카오시발", "0개시발", "8시발", "입어보지시발", "전자지갑시발"})
    @DisplayName("화이트리스트가 우회로를 만들지 않는다 — 조각을 붙여도 토큰이 통과하지 않는다")
    void theWhitelistDoesNotOpenBypasses(String token) {
        assertThat(filterFromDictionary().filter(token))
                .as("'%s' 이(가) 통째로 통과한다 — 화이트리스트 항목이 우회로가 됐다", token)
                .isNotEqualTo(token);
    }

    /**
     * 화이트리스트의 형식 불변식.
     *
     * <p>목록이 커지면 사람 눈으로는 못 지킨다. 세 가지가 전부 "조용히 사문이 되거나 우회로가
     * 되는" 모양이라 데이터 자체를 검사한다.
     */
    @Test
    @DisplayName("화이트리스트 항목은 전부 '욕설을 품은 더 긴 정상어' 다")
    void everyWhitelistEntryCoversSomeProfanity() {
        ProfanityDictionary dictionary = ProfanityDictionary.load(null, null);
        var words = dictionary.words();

        assertThat(dictionary.whitelist()).allSatisfy(entry -> {
            assertThat(words)
                    .as("'%s' 은(는) 맨 욕설이다 — 넣으면 진짜 욕설까지 풀린다", entry)
                    .doesNotContain(entry);
            assertThat(words.stream().anyMatch(entry::contains))
                    .as("'%s' 은(는) 사전 단어를 품지 않는다 — 보호할 대상이 없고 우회로만 만든다", entry)
                    .isTrue();
        });
    }

    @Test
    @DisplayName("설정 단어는 사전에 더해진다 — 교체면 설정 한 줄이 사전을 날린다")
    void configuredWordsAreAddedNotReplacing() {
        ProfanityDictionary dictionary = ProfanityDictionary.load("김치찌개", "김치찌개맛집");

        assertThat(dictionary.words())
                .as("설정 단어가 들어오지 않았다").contains("김치찌개")
                .as("설정이 기본 사전을 교체했다").hasSizeGreaterThan(100);
        assertThat(dictionary.whitelist())
                .as("설정 화이트리스트가 들어오지 않았거나 기본을 교체했다")
                .contains("김치찌개맛집").contains("시발점");
    }

    @Test
    @DisplayName("빈 설정은 무시한다 — null·공백이 사전을 흔들지 않는다")
    void blankConfigIsIgnored() {
        ProfanityDictionary base = ProfanityDictionary.load(null, null);
        ProfanityDictionary blank = ProfanityDictionary.load("  ", ", ,");

        assertThat(blank.words()).isEqualTo(base.words());
        assertThat(blank.whitelist()).isEqualTo(base.whitelist());
    }

    @Test
    @DisplayName("리소스가 없으면 부팅을 실패시킨다 — 조용한 pass-through 보다 시끄러운 실패가 낫다")
    void aMissingResourceFailsBoot() {
        assertThatThrownBy(() -> ProfanityDictionary.load("/chat/없는-파일.txt", WHITELIST, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("욕설 사전 리소스가 없다")
                .as("조치가 아니라 사실만 적혀 있다 — 당직자가 무엇을 봐야 할지 알 수 없다")
                .hasMessageContaining("마스킹이 조용히 꺼지므로");
    }

    /**
     * 읽히긴 했으나 단어가 0개여도 실패다.
     *
     * <p>처음에는 같은 디렉터리의 라이선스 고지 파일을 "빈 사전" 대용으로 썼는데 통과했다 —
     * 그 파일은 {@code #} 주석이 아니라 산문이라 로더가 <b>라이선스 문장을 단어로 읽는다.</b>
     * 그래서 주석만 든 테스트 리소스를 따로 뒀다. 운영에서 이 모양이 되는 경로는 "단어를 지우고
     * 설명만 남기는" 편집이다. (그 산문 함정 자체는 아래 길이 상한이 닫는다.)
     */
    @Test
    @DisplayName("사전이 주석·빈 줄뿐이면 그것도 실패다 — 단어 0개는 기능 꺼짐과 같다")
    void aCommentsOnlyResourceFailsBoot() {
        assertThatThrownBy(() -> ProfanityDictionary.load(
                "/chat/comments-only-dictionary.txt", WHITELIST, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("비어 있다");
    }

    @Test
    @DisplayName("공백이 든 항목은 거부한다 — 어떤 토큰에도 안 걸리는 사문이 조용히 쌓인다")
    void anEntryWithWhitespaceFailsBoot() {
        assertThatThrownBy(() -> ProfanityDictionary.load(
                "/chat/whitespace-entry-dictionary.txt", WHITELIST, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("공백이 있다")
                .as("어느 줄인지 안 알려주면 1,000줄짜리 파일에서 찾을 수 없다")
                .hasMessageContaining(":5");
    }

    @Test
    @DisplayName("상한을 넘는 항목은 거부한다 — 단어 파일에 산문이 섞인 것이다")
    void anOverlongEntryFailsBoot() {
        assertThatThrownBy(() -> ProfanityDictionary.load(
                "/chat/long-entry-dictionary.txt", WHITELIST, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("너무 길다");
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
