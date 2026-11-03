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
            // '졸라' 는 없다 — 조르다의 활용(졸라요·졸라네)과 겹쳐 사전에서 뺐다. 같은 뜻의
            // '존나' 가 그 자리를 지킨다.
            "시발", "씨발", "씨팔", "ㅅㅂ", "ㅆㅂ", "존나", "좆", "썅",
            "개새끼", "병신", "븅신", "지랄", "염병", "니미", "애미",
            "미친놈", "미친년", "창녀", "강간", "쌍놈", "씹창", "s1bal", "sibal",
            // 'sex' 를 사전에서 빼면서 sexual·sexy 화이트리스트가 같이 빠졌다 — ASCII 자판만으로
            // 사전을 끄던 키가 사라진 자리다.
            "sexyfuck", "sexy시발", "SEXY시발", "시발sexual",
            // 손가락. 화이트리스트에서 ㅗ 조합을 전부 떨어뜨렸기 때문에 이제 실제로 잡힌다.
            "ㅗ", "ㅗㅜㅑ"})
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
            "고환 검사", "한우 고환 손질", "후장 시세", "후장 마감 기준",
            // 아래는 화이트리스트를 거르지 않고 통째로 교체했다가 보호를 잃은 것들이다.
            "일시불 결제", "유니섹스 디자인", "전염병 주의", "감염병 예방",
            "무시발언 자제", "다시팔게요", "오랄비 칫솔",
            // 1글자·연결형이라 결합이 무제한인 것들 — 사전에서 뺐다.
            "똥손이라 죄송", "소똥 거름", "허리가 쑤셔요",
            // 조르다의 활용. 활용형 넷을 화이트리스트에 열거해 봤지만 닫히지 않아 '졸라' 를 뺐다.
            "애가 졸라서 샀어요", "애가 졸라요", "졸라네요", "졸라댔어요", "허리띠를 졸라매고",
            // 'sex' 가 사전에 있는 동안 평범한 영단어가 가려졌다.
            "UNISEX 사이즈 있나요", "unisex 사이즈", "Essex 산 제품",
            // <시각>시발송 — '구매시발송' 만 막아 두는 바람에 숫자 쪽이 열려 있었다.
            "8시발송", "9시발송", "오후 8시발송입니다",
            // 조르다의 활용. 활용형을 넷 열거해 봤지만 닫히지 않아 '졸라' 를 사전에서 뺐다.
            // 라틴 항목이 평범한 영단어에 걸린 것들 — sex 와 siva 를 뺐다.
            "passivation 처리", "passivate 공정", "Essex 산", "BusinessException 로그"})
    @DisplayName("평범한 한국어는 그대로 나간다 — 판매자 설명이 검열되면 되돌릴 수 없다")
    void ordinaryKoreanIsNotMasked(String sentence) {
        assertThat(filterFromDictionary().filter(sentence))
                .as("'%s' 이(가) 가려졌다 — 짧은 항목이 한국어 형태소와 겹친다", sentence)
                .isEqualTo(sentence);
    }

    /**
     * 보이지 않는 글자를 끼워도 마스킹된다 — <b>매처</b> 쪽 이야기다.
     *
     * <p>로더가 사전 항목의 보이지 않는 글자를 거부하는 것과 이것은 다른 평면이다. 로더는
     * 운영자 복붙 경로라 공격자가 닿지 않고, 매처는 <b>시청자 누구나</b> 닿는다. 한글 채움문자
     * 넷은 유니코드가 '문자'(Lo)로 분류해 2A 전처리({@code Character.isLetter})를 그냥 지나갔고,
     * 토큰 정규식도 구분자로 보지 않아 <b>화면에 공백으로 보이는 한 글자로 사전 전체가 꺼졌다.</b>
     * 다른 보이지 않는 글자는 글자가 아니라 이미 떨어졌고 정확히 이 넷만 통과했다.
     */
    @ParameterizedTest(name = "U+{0}")
    @ValueSource(strings = {"115F", "1160", "3164", "FFA0", "00A0", "200B", "2060", "FEFF", "FE0F"})
    @DisplayName("⭐ 보이지 않는 글자를 끼워 넣어도 매처가 잡는다 — 로더만 닫으면 공격자 면이 열린다")
    void anInvisibleCharacterDoesNotEvadeTheMatcher(String codePoint) {
        String evaded = "개" + Character.toString(Integer.parseInt(codePoint, 16)) + "새끼";

        assertThat(filterFromDictionary().filter(evaded))
                .as("U+%s 를 끼우면 통과한다 — 이 한 글자로 사전 전체가 무력화된다", codePoint)
                .isNotEqualTo(evaded);
    }

    /**
     * 같은 글자로 보이는 입력은 같게 판정한다.
     *
     * <p>NFD 분해형과 전각은 <b>화면에 똑같이 보인다.</b> 접지 않으면 사전 1,106개가 통째로
     * 지나가는데, 채움문자와 달리 글자를 끼울 필요조차 없다 — macOS 복사·전각 IME 로 그냥
     * 나온다. {@code displayMessage == originalMessage} 로 영속·팬아웃되고 화면상 구별이
     * 불가능하다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"아 시발 진짜", "개새끼", "병신아", "씨발년", "존나"})
    @DisplayName("⭐ NFD 분해형도 마스킹된다 — 화면이 같은데 매처만 다르게 보면 사전 전체가 꺼진다")
    void aDecomposedMessageIsStillMasked(String message) {
        String decomposed = java.text.Normalizer.normalize(message, java.text.Normalizer.Form.NFD);

        assertThat(filterFromDictionary().filter(decomposed))
                .as("'%s' 의 NFD 형이 통과한다 — 보낸 사람 말고는 아무도 구별하지 못한다", message)
                .isNotEqualTo(decomposed);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ｆｕｃｋ", "ｓｉｂａｌ", "ｂｉｔｃｈ", "ＦＵＣＫ"})
    @DisplayName("⭐ 전각 라틴도 마스킹된다 — ASCII 와 1:1 이라 길이 보존으로 접을 수 있다")
    void aFullwidthMessageIsStillMasked(String message) {
        assertThat(filterFromDictionary().filter(message))
                .as("'%s' 가 통과한다 — 전각 IME 하나로 사전의 라틴 항목이 전부 꺼진다", message)
                .isNotEqualTo(message);
    }

    /**
     * 호환 분해는 접지 않는다 — 접으면 판매자 문구를 고쳐 쓴다.
     *
     * <p>{@code displayMessage} 가 영속되는 본문이다. NFKC 를 쓰면 ㈜사파리 → (주)사파리 ·
     * Ⅲ단계 → III단계 · ①번 → 1번 으로 바뀐다(실측). 길이도 1:1 이 아니라 span 슬라이싱 계약이
     * 함께 깨진다. 그래서 NFC + 길이 보존 폴딩까지만 한다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"㈜사파리 공식몰", "Ⅲ단계 세럼", "①번 상품", "½ 사이즈", "３만원 특가"})
    @DisplayName("호환 분해 글자는 원문 그대로 둔다 — 영속되는 본문을 고쳐 쓰지 않는다")
    void compatibilityFormsAreLeftAlone(String message) {
        assertThat(filterFromDictionary().filter(message)).isEqualTo(message);
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
     * 맨 욕설·보호 대상 없는 항목이 화이트리스트에 들어가 우회로가 되지 않는다.
     *
     * <p>이 저장소의 화이트리스트 의미는 "토큰이 항목을 <b>포함</b>하면 그 토큰 전체를 보존" 이다
     * ({@code ProfanityFilter.isWhitelisted} → {@code containsAny}). 그래서 항목을 하나 잘못 넣으면
     * 그 조각을 붙인 토큰이 통째로 통과한다. 실제로 두 번 밟았다 — 맨 욕설에 숫자를 붙인
     * {@code 8시발}, 그리고 사전에 없는 욕설을 보호하던 {@code 입어보지} 류 24건이다.
     *
     * <p>⚠️ 이 테스트가 재는 것은 <b>그 두 종류가 목록에 없다</b>는 것뿐이다. 규칙을 지킨 항목도
     * 여전히 우회 스위치다 — {@code 시발점씨발} 은 지금도 통째로 통과한다(유지된 31건 전수 실측).
     * 그 잔재는 데이터가 아니라 {@code isWhitelisted} 가 "항목을 포함하면 보존" 이라는 데서 나오고,
     * 닫으려면 필터를 바꿔야 한다. 이 테스트를 "우회로가 없다" 의 증거로 읽지 말 것.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"너가시발", "카카오시발", "0개시발", "8시발", "입어보지시발", "전자지갑시발"})
    @DisplayName("맨 욕설·보호 대상 없는 항목이 화이트리스트에 없다 — 둘 다 우회로를 연다")
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
            String residue = entry;
            for (String word : words) {
                residue = residue.replace(word, "");
            }
            assertThat(residue.chars().anyMatch(c -> c >= 0xAC00 && c <= 0xD7A3))
                    .as("'%s' 은(는) 욕설을 빼면 한글이 남지 않는다 — 지킬 '말' 이 없고 우회로만 만든다",
                            entry)
                    .isTrue();
        });
    }

    /**
     * 순 ASCII 항목의 집합을 통째로 고정한다.
     *
     * <p>라틴 항목은 평범한 영단어의 부분문자열이 되기 쉽고, 한글 항목과 달리 화이트리스트로
     * 구제할 수 없다 — 잔여가 라틴이라 규칙 (3)에 걸린다. 그래서 들어오는 순간이 유일한 통제
     * 지점인데, 두 번 연속 사후에 발견했다({@code sex} → BusinessException,
     * {@code siva} → passivation).
     *
     * <p>영단어 목록을 테스트에 들이지는 않는다. OS 제공 파일이라 CI 이미지에 없으면 검사가
     * <b>조용히 초록</b>이 되고, 그게 이 티켓에서 반복된 바로 그 모양이다. 대신 집합을 박는다 —
     * 항목을 더하려면 이 테스트를 고쳐야 하고, 고치려면 사전 머리글의 대조 절차를 보게 된다.
     * 그 문단에 목록·단어 수·날짜·명령이 적혀 있다.
     */
    @Test
    @DisplayName("⭐ 순 ASCII 사전 항목은 이 28건뿐이다 — 더하려면 영단어 대조를 다시 돌려야 한다")
    void theAsciiEntriesAreExactlyThese() {
        var ascii = ProfanityDictionary.load(null, null).words().stream()
                .filter(w -> w.matches("[A-Za-z]+"))
                .map(String::toLowerCase)
                .sorted()
                .toList();

        assertThat(ascii).containsExactlyInAnyOrder(
                "bitch", "bozi", "fuck", "fuckyou", "giral", "penis", "pennis", "pussy",
                "rotorl", "rotprl", "siba", "sibak", "sibal", "sibar", "sipal", "siqk",
                "sival", "tlba", "tlbak", "tlbal", "tlbar", "tlpal", "tlqk", "tlqkd",
                "tlqkf", "tlva", "tlval", "wlfkf");
    }

    /**
     * 화이트리스트 안에 서로 덮는 항목이 없다.
     *
     * <p>다른 항목에 이미 덮이는 항목은 결과를 바꾸지 못하면서 우회면만 넓힌다
     * ({@code 시발자} 가 있으면 {@code 시발자동차} 는 사문이다).
     *
     * <p>사전 단어가 화이트리스트 항목을 품는 방향은 <b>단언하지 않는다.</b> 한때 그것도
     * 걸었는데 "그러면 영원히 안 걸린다" 는 전제가 거짓이었다 — 리터럴 토큰만 보존되고
     * 2A·2B 우회형은 잡힌다. 그 단언을 남겨 두면 다음 사람이 정당한 번들 단어를 지우는 쪽으로
     * 확장한다.
     */
    @Test
    @DisplayName("화이트리스트에 서로 덮는 항목이 없다 — 효과 0 이면서 우회면만 넓힌다")
    void theBundledDataHasNoDeadEntries() {
        ProfanityDictionary dictionary = ProfanityDictionary.load(null, null);
        var words = dictionary.words();
        var whitelist = dictionary.whitelist();

        assertThat(words).isNotEmpty();
        assertThat(whitelist).allSatisfy(entry ->
                assertThat(whitelist.stream().filter(o -> !o.equals(entry) && entry.contains(o)).toList())
                        .as("'%s' 은(는) 더 짧은 항목에 이미 덮인다 — 결과를 안 바꾸고 우회면만 넓힌다", entry)
                        .isEmpty());
    }

    @Test
    @DisplayName("설정 단어는 사전에 더해진다 — 교체면 설정 한 줄이 사전을 날린다")
    void configuredWordsAreAddedNotReplacing() {
        // 화이트리스트 값은 words 에 방금 더한 단어를 품어야 한다 — 의미 검사가 그것을 요구한다.
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
                .hasMessageContaining("보이지 않는 글자가 있다")
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

    /**
     * 설정 CSV 도 같은 검사를 지난다.
     *
     * <p>처음에는 리소스에만 걸었다. 그러면 급한 차단이 필요한 바로 그 순간에 조용히 실패한다 —
     * 방송 중에 {@code CHAT_PROFANITY_WORDS=개 새끼} 를 넣으면 부팅도 되고 적재 로그의 개수도
     * 늘지만 아무것도 막지 못한다. 반대 방향은 더 나쁘다 — 설정 화이트리스트에 맨 욕설 한 줄이
     * 들어가면 그 욕설이 사전 전체에서 풀렸다.
     */
    @Test
    @DisplayName("설정 CSV 의 공백 항목도 거부한다 — 긴급 통로가 조용히 사문이 되면 안 된다")
    void aWhitespaceEntryFromConfigFailsBoot() {
        assertThatThrownBy(() -> ProfanityDictionary.load("시발,,개 새끼", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("보이지 않는 글자가 있다")
                .as("어느 프로퍼티인지 없으면 두 키 중 어느 쪽인지 모른다")
                .hasMessageContaining("chat.profanity.words")
                .as("몇 번째인지 없으면 긴 CSV 에서 못 찾는다 — 빈 칸도 세야 운영자가 보는 위치와 맞는다")
                .hasMessageContaining("3번째 항목");
    }

    @Test
    @DisplayName("설정 CSV 의 상한 초과 항목도 거부한다")
    void anOverlongEntryFromConfigFailsBoot() {
        assertThatThrownBy(() -> ProfanityDictionary.load(null, "가".repeat(65)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("너무 길다")
                .hasMessageContaining("chat.profanity.whitelist");
    }

    /**
     * 설정 화이트리스트는 모양만이 아니라 의미도 검사한다.
     *
     * <p>모양 검사(공백·길이)만 있을 때 {@code chat.profanity.whitelist=시발} 한 줄이 그대로
     * 통과했고, 그 한 줄로 사전 전체에서 그 욕설이 풀렸다 — 실측에서 부팅이 성공하고
     * {@code 아 시발 짜증나} 가 마스킹되지 않았다. 번들 데이터에는 같은 불변식을
     * {@code everyWhitelistEntryCoversSomeProfanity} 가 강제하고 있었는데 설정 입구에만 없었다.
     */
    @Test
    @DisplayName("설정 화이트리스트에 맨 욕설을 넣으면 부팅이 실패한다 — 한 줄로 사전 전체가 풀린다")
    void aBareProfanityInTheConfiguredWhitelistFailsBoot() {
        assertThatThrownBy(() -> ProfanityDictionary.load(null, "시발"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("욕설 그 자체다")
                .as("무엇을 넣어야 하는지 없으면 운영자가 같은 실수를 반복한다")
                .hasMessageContaining("시발점");
    }

    @Test
    @DisplayName("욕설을 품지 않은 설정 화이트리스트 항목도 거부한다 — 우회로만 만든다")
    void aConfiguredWhitelistEntryThatProtectsNothingFailsBoot() {
        assertThatThrownBy(() -> ProfanityDictionary.load(null, "김치찌개"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("어떤 욕설도 품지 않는다");
    }

    @Test
    @DisplayName("대문자로 적어도 맨 욕설은 거부한다 — 런타임이 소문자로 맞추므로 입구도 그래야 한다")
    void aBareProfanityInDifferentCaseIsStillRejected() {
        assertThatThrownBy(() -> ProfanityDictionary.load(null, "sibaL"))
                .as("런타임은 asciiLower 위에서 도는데 입구만 대소문자를 가리면, 같은 값이 표기에 "
                        + "따라 통과하거나 거부되고 거부 메시지도 거짓이 된다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("욕설 그 자체다");
    }

    @Test
    @DisplayName("규칙 (3)도 소문자 평면에서 본다 — 대문자로 적어도 잔여가 '8' 이면 거부한다")
    void theResidueRuleRunsOnTheLowercasePlane() {
        assertThatThrownBy(() -> ProfanityDictionary.load(null, "8SIBAL"))
                .as("입구가 매처와 다른 평면에서 판정하면 표기만 바꿔 우회로가 들어온다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("한글이 남지 않는다");
    }

    @Test
    @DisplayName("규칙 (2)도 소문자 평면에서 본다 — 대문자로 적은 보호어를 '욕설 미포함' 으로 보지 않는다")
    void theCoverageRuleRunsOnTheLowercasePlane() {
        ProfanityDictionary dictionary = ProfanityDictionary.load(null, "SIBAL점");

        assertThat(dictionary.whitelist())
                .as("소문자로 맞추지 않으면 'sibal' 을 못 찾아 정당한 보호어가 거부된다")
                .contains("SIBAL점");
    }

    /**
     * 욕설을 뺀 잔여에 한글이 없으면 지킬 '말' 이 없다.
     *
     * <p>{@code 8시발} 의 잔여는 {@code 8} 뿐이다. 정상어를 지키는 게 아니라 숫자를 붙인 우회로를
     * 만든다. 한때 영문 {@code sexual}·{@code sexy} 를 이 규칙의 예외로 뒀는데 그 둘이 ASCII
     * 자판만으로 사전을 끄는 키였다 — 사전에서 {@code sex} 를 빼면서 예외가 없어졌고, 그래서
     * 이제 세 규칙을 로더가 예외 없이 강제한다.
     */
    @Test
    @DisplayName("욕설을 뺀 잔여가 한글이 아니면 거부한다 — '8시발' 은 '8' 을 붙인 우회로다")
    void aWhitelistEntryWithNoHangulResidueIsRejected() {
        assertThatThrownBy(() -> ProfanityDictionary.load(null, "8시발"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("한글이 남지 않는다");
    }

    /**
     * 화이트리스트에 일부 가려지는 단어는 <b>적재하고 알린다</b> — 거부하지 않는다.
     *
     * <p>처음에는 "영원히 매치되지 않는다" 로 보고 부팅을 세웠는데 그 전제가 거짓이었다.
     * 리터럴 토큰 하나만 보존되고 2A·2B 우회 경로는 화이트리스트를 보지 않는다 — 다섯 모양 중
     * 넷의 동작이 바뀐다. 거부하면 방송 중에 쓰라고 둔 통로가 정당한 긴급차단을 막고, 그 실패가
     * 빈 생성 실패 = 전 파드 채팅 정지다.
     */
    @Test
    @DisplayName("⭐ 일부 가려지는 단어도 적재한다 — 우회형은 여전히 잡고, 거부하면 긴급차단이 막힌다")
    void aPartiallyShadowedWordIsLoadedAndReported() {
        ProfanityDictionary dictionary = ProfanityDictionary.load("전염병같은놈", null);
        ProfanityFilter filter = new ProfanityFilter(dictionary.words(), dictionary.whitelist());

        assertThat(dictionary.words()).contains("전염병같은놈");
        assertThat(dictionary.partiallyShadowed())
                .as("가려진다는 사실을 호출자가 알 수 없으면 '막았다' 와 구분이 안 된다")
                .anySatisfy(entry -> assertThat(entry).contains("전염병같은놈").contains("전염병"));

        assertThat(filter.filter("전염병 같은놈"))
                .as("우회형까지 못 잡는다면 이 단어는 정말 쓸모가 없고, 그러면 거부가 맞았을 것이다")
                .isNotEqualTo("전염병 같은놈");
        assertThat(filter.filter("전염병1같은놈")).isNotEqualTo("전염병1같은놈");
        assertThat(filter.filter("전염병같은놈"))
                .as("리터럴 토큰은 화이트리스트가 보존한다 — 이것이 '일부' 라는 말의 내용이다")
                .isEqualTo("전염병같은놈");
    }

    /**
     * 설정 단어가 화이트리스트 항목과 <b>정확히 같아도</b> 보고된다.
     *
     * <p>화이트리스트끼리 비교할 때는 자기 자신을 빼야 모든 항목이 자기에게 덮였다고 나온다.
     * 그 함수를 단어 방향에 그냥 재사용했다가 값이 같은 경우를 "자기비교" 로 건너뛰었다 —
     * 두 집합은 다르므로 값이 같은 것은 <b>완전히 가려진 단어</b>이고, 가장 크게 보고해야 할
     * 경우가 유일하게 조용해졌다.
     */
    @Test
    @DisplayName("설정 단어가 화이트리스트 항목과 같으면 그것도 보고한다 — 가장 크게 가려진 경우다")
    void aWordIdenticalToAWhitelistEntryIsStillReported() {
        ProfanityDictionary dictionary = ProfanityDictionary.load("시발점", null);

        assertThat(dictionary.partiallyShadowed())
                .as("값이 같으면 자기비교가 아니라 완전히 가려진 것이다")
                .anySatisfy(entry -> assertThat(entry).contains("시발점"));
    }

    @Test
    @DisplayName("보이지 않는 글자가 섞여도 거부한다 — 공백보다 찾기 어려운 사문이다")
    void anInvisibleCharacterEntryFailsBoot() {
        assertThatThrownBy(() -> ProfanityDictionary.load("개\u200B새끼", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("보이지 않는 글자")
                .as("원문을 그대로 실으면 보이지 않는 글자를 지목할 수 없다")
                .hasMessageContaining("u200B");
    }

    /**
     * 한글 채움문자는 넷이고 전부 Lo(문자)로 분류된다.
     *
     * <p>{@code isWhitespace} 도 {@code isSpaceChar} 도 {@code FORMAT} 도 이들을 걸러 내지
     * 못한다. 처음에 U+3164·U+1160 둘만 막았는데, 남긴 둘이 그대로 통로였다 — 셋을 막고
     * 하나를 두는 것은 막지 않은 것과 같다.
     */
    @ParameterizedTest(name = "U+{0}")
    @ValueSource(strings = {
            "115F", "1160", "3164", "FFA0",   // 한글 채움문자 넷 — 전부 Lo 라 표준 검사에 안 걸린다
            "00A0", "200B", "2060", "FEFF",   // NBSP · ZWSP · 워드조이너 · BOM
            "FE0F", "0300",                   // 변이 선택자 · 결합 악센트 — 폭 0 인 Mn
            "E0001",                          // 태그 문자 — 보조평면이라 char 루프로는 안 잡힌다
            "2800", "FFFC"})                  // 공백처럼 렌더되는 So — 분류로는 안 잡혀 열거로 받는다
    @DisplayName("보이지 않는 글자를 분류로 거부한다 — 열거는 빠뜨린 하나가 통로가 된다")
    void everyInvisibleCharacterIsRejected(String codePoint) {
        String entry = "개" + Character.toString(Integer.parseInt(codePoint, 16)) + "새끼";

        assertThatThrownBy(() -> ProfanityDictionary.load(entry, null))
                .as("U+%s 가 통과한다 — 이 항목은 적재되고도 평범한 입력을 영원히 못 잡는다", codePoint)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("보이지 않는 글자");
    }

    /**
     * 이미 덮이는 화이트리스트 항목은 <b>적재하고 알린다</b> — 거부하지 않는다.
     *
     * <p>거부가 남아 있는 셋(맨 욕설 · 보호 대상 없음 · 잔여 비한글)은 전부 <b>우회로를 새로
     * 연다.</b> 이 항목은 그렇지 않다 — 긴 항목을 품은 토큰은 짧은 항목도 반드시 품으므로 결과가
     * 바뀌지 않는다. 한때 이것만 거부로 뒀는데, 그러면 효과가 4/5 인 값(가려진 단어)은 통과시키고
     * 효과가 0 인 값에 전 파드 채팅을 세우는 거꾸로 된 정책이 된다. 경계는 '쓸모없음' 이 아니라
     * '해로움' 이다.
     */
    @Test
    @DisplayName("⭐ 이미 덮이는 항목은 적재하고 알린다 — 해롭지 않은 것에 부팅을 세우지 않는다")
    void aWhitelistEntryAlreadyCoveredIsLoadedAndReported() {
        ProfanityDictionary dictionary = ProfanityDictionary.load(null, "시발자동차");

        assertThat(dictionary.whitelist()).contains("시발자동차");
        assertThat(dictionary.redundantWhitelist())
                .as("효과 0 인 항목이 쌓이는 것을 알리지 않으면 우회면만 조용히 넓어진다")
                .anySatisfy(e -> assertThat(e).contains("시발자동차").contains("시발자"));
    }

    /**
     * NBSP 도 공백으로 본다.
     *
     * <p>{@code Character.isWhitespace(U+00A0)} 는 <b>false</b> 이고 {@code ProfanityFilter} 의
     * 토큰 정규식 {@code \S+} 도 NBSP 를 구분자로 보지 않는다. 그래서 NBSP 가 섞인 항목은
     * 거부되지도 않고 평범한 공백이 든 입력을 잡지도 못한다 — 막았다고 보이는데 아무것도
     * 막지 않는, {@code reject} 가 존재하는 이유인 그 모양이다. 복붙이 NBSP 를 만드는 전형
     * 경로이고 설정 CSV 에는 번들 리소스 같은 사전 검사가 없다.
     */
    @Test
    @DisplayName("NBSP 가 섞인 설정 항목도 거부한다 — 막은 것처럼 보이지만 아무것도 안 막는다")
    void aNonBreakingSpaceEntryFromConfigFailsBoot() {
        assertThatThrownBy(() -> ProfanityDictionary.load("개\u00A0새끼", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("보이지 않는 글자가 있다");
    }

    /**
     * BOM 이 붙어도 1행이 주석으로 읽힌다.
     *
     * <p>{@code strip()} 은 {@code Character.isWhitespace} 기준이라 BOM(U+FEFF)을 떼지 않는다.
     * 떼지 않으면 1행이 {@code '#'} 으로 시작하지 않게 되어 주석 판정을 비껴가고, 짧아서 길이
     * 상한에도 안 걸려 <b>그 줄이 단어가 된다.</b>
     */
    @Test
    @DisplayName("BOM 이 붙은 1행은 주석으로 읽힌다 — 안 떼면 주석 줄이 단어로 적재된다")
    void aByteOrderMarkDoesNotTurnTheFirstLineIntoAWord() {
        ProfanityDictionary dictionary =
                ProfanityDictionary.load("/chat/bom-dictionary.txt", WHITELIST, null, null);

        assertThat(dictionary.words())
                .as("BOM 이 남아 주석 줄이 단어가 됐다")
                .containsExactly("시발");
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
