package com.sapari.chat.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import com.sapari.chat.application.port.ProfanityFilterFactory;
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
            "sexyfuck", "sexy시발", "SEXY시발", "시발sexual"})
            // 손가락 'ㅗ' 는 여기 있었다. 2026-11-04 에 사전에서 뺐다 — 1코드포인트라 결합이
            // 무제한이고 'ㅗㅜㅑ'(흔한 감탄 표기) · '사이즈 ㅗ' 를 가렸다. 같은 기준으로 '똥' 을
            // 뺀 선례가 사전 머리글에 있고, 그 집계가 [가-힣] 기준이라 자모를 안 세서 이 계급의
            // 회귀가 돈 흔적이 없었다. 아래 ordinaryKoreanSurvives 가 반대 방향을 든다.
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
            // 라틴 항목이 평범한 영단어에 걸린 것들 — sex 와 siva 를 뺐다.
            "passivation 처리", "passivate 공정", "Essex 산", "BusinessException 로그",
            // '색스'·'s스' 가 사전에 있는 동안 토큰 안의 기호를 뗀 2A 가 이것들을 통째로 가렸다.
            "DB/Redis/검색/스토리지", "검색스토리지 설정", "as(\"스레드",
            // 보호어 뒤에 붙은 용언 어미. 화이트 span 이 사전 span 을 못 덮어 걸쳐 남은 자리다 —
            // '섹스해'·'섹스하자' 를 빼고 화이트 '일시불알' 을 넣어 닫았다.
            "이 옷 유니섹스해요", "디자인이 유니섹스해서 좋아요", "유니섹스하자",
            "일시불알려주세요", "일시불알림",
            // 보호어 둘이 맞닿은 자리. 거르기를 병합보다 먼저 하지 않으면 전***송! 이 된다.
            "전염병시발송!",
            // 보호어 뒤에 붙은 <명사>. 어미가 아니라 명사라서 접미 스윕 60종이 못 봤다.
            "감염병신고 접수", "전염병신고센터", "화염병신고", "전염병신규 확진", "감염병신속진단",
            "시발점물류", "시발택시발견", "다시팔년치",
            // 화이트리스트와 무관한 같은 계급. 사전에서 점물·육갑 을 빼서 닫았다.
            "지점물류", "매점물건", "거점물류센터", "교육갑니다", "체육갑니다", "성교육갑니다",
            // 조사는 붙여 쓰는 것이 정문법이다 — 공백 누락이 아니라 올바른 문장이었다.
            "구조까지", "참조까지", "충족까지 확인", "부족까지", "구조또는 참조", "보조또는",
            "새우젓까지", "액젓까지", "멸치액젓까지 주문",
            // 공백 없이 쓰는 복합명사. 화이트리스트 환불알·동봉알·검색키 로 닫았다.
            "환불알림", "환불알려주세요", "환불알고리즘", "동봉알림", "검색키", "검색키워드",
            // 2분할 계급 (2026-11-04). 2B 는 인접 토큰을 이어 붙이고 <조인 결과가 사전 항목과
            // 정확히 같을 때> 발화하므로, 양쪽이 각각 평범한 낱말이면 띄어 써도 걸린다. 머리글에
            // "공백이 있으면 전부 원문이 나간다" 고 적혀 있었고 그것이 거짓이었다 — 사전 1,078건
            // 중 1,048건이 안쪽 공백 하나로 걸린다. 사전 15건을 빼서 닫았다.
            "개 세 마리 키워요", "강아지 세 마리랑 개 세 마리", "게 같은 갑각류 알레르기",
            "개 똥 치우는 봉투", "개똥쑥차", "개똥쑥 말린 것", "띠 풀 뿌리 말린 거",
            "나사를 박고 빼고 반복하면 됩니다", "개가 든 가방이에요",
            "남성교복", "여성교사", "완성교체", "음성교환", "영속성 교체",
            "에비앙 생수", "에미레이트 항공", "에미상 후보",
            "3개년 계획", "5개년계획 수립", "중개세무 법인", "소개세션 안내",
            "기저년도 대비", "돌을 쪼다", "정을 대고 쪼다",
            // '…상년도' 는 사전 '상년' 에 걸렸다. 접미 하나가 계급 전체를 덮는 드문 자리라
            // 화이트리스트 '상년도' 로 닫았다 — 열거가 아니다.
            "예상년도", "결산년도", "기준년도", "회계 예상년도 기준",
            // 1코드포인트 자모. 'ㅗ' 를 뺐다 — 결합이 무제한이고 흔한 감탄 표기를 가렸다.
            "ㅗㅜㅑ", "사이즈 ㅗ", "ㅗㅗ",
            // 이 저장소 자신의 주석이 검열되고 있었다 — '돌아이' 를 빼서 닫았다(표준 표기
            // '또라이' 는 남는다).
            "그쪽은 시큐리티 체인이 먼저 돌아 401 이 오는데", "돌아 이 제품 보세요"})
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
     * <p>NFD 분해형과 전각은 <b>화면에 똑같이 보인다.</b> 접지 않으면 사전 1,078개가 통째로
     * 지나가는데, 채움문자와 달리 글자를 끼울 필요조차 없다 — macOS 복사·전각 IME 로 그냥
     * 나온다. {@code displayMessage == originalMessage} 로 영속·팬아웃되고 화면상 구별이
     * 불가능하다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"아 시발 진짜", "개새끼", "병신아", "씨발년", "존나"})
    @DisplayName("⭐ NFD 분해형도 마스킹된다 — 화면이 같은데 매처만 다르게 보면 사전 전체가 꺼진다")
    void aDecomposedMessageIsStillMasked(String message) {
        String decomposed = java.text.Normalizer.normalize(message, java.text.Normalizer.Form.NFD);

        // isNotEqualTo 로는 못 잰다 — filter 가 NFC 를 돌려주므로 마스킹이 0 건이어도 반환값이
        // 입력과 다르다. 마스킹 자체를 봐야 한다.
        assertThat(filterFromDictionary().filter(decomposed))
                .as("'%s' 의 NFD 형이 통과한다 — 보낸 사람 말고는 아무도 구별하지 못한다", message)
                .contains("***");
    }

    /**
     * NFD 는 음절 <b>안</b>에 글자를 끼웠을 때가 진짜 시험대다.
     *
     * <p>음절 경계에 끼우면 입구 NFC 가 합성해 잡히지만, 자모 사이에 끼우면 합성이 막힌다 —
     * 한때 76케이스 중 57 이 통과했다. 떼고 나서 합치는 순서로 닫았다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"1", "!", ".", "-", "*", "0", " ", "\u200B", "\u3164", "\uFE0F"})
    @DisplayName("⭐ NFD 음절 안에 글자를 끼워도 잡는다 — 떼기 전에 합치면 영원히 안 붙는다")
    void anInsertionInsideADecomposedSyllableIsStillMasked(String inserted) {
        String nfd = java.text.Normalizer.normalize("시발", java.text.Normalizer.Form.NFD);
        ProfanityFilter filter = filterFromDictionary();

        for (int pos = 1; pos < nfd.length(); pos++) {
            String evaded = nfd.substring(0, pos) + inserted + nfd.substring(pos);
            assertThat(filter.filter(evaded))
                    .as("'%s' 를 %d번째에 끼우면 통과한다 — 화면에는 그냥 시발이다", inserted, pos)
                    .contains("***");
        }
    }

    /**
     * 결합표시(Mn)를 끼워도 막는다 — 입구 NFC 가 합쳐 버려서 뗄 것이 안 남는다.
     *
     * <p>{@code u} 뒤에 U+0300 을 붙이면 NFC 가 {@code ù} 로 합치고 그건 글자라서 2A 전처리가
     * 떼어내지 못한다. 그래서 라틴 항목 73건 중 <b>51건</b>이 Mn 한 글자로 통과했다(실측).
     * 평면 A 가 NFD 로 먼저 쪼개 Mn 을 드러내야 닫힌다 — 한글은 영향이 없다(쪼갠 자모가 다시
     * 합쳐진다). 그 선행 NFD 를 빼면 아래가 깨진다.
     *
     * <p>악센트가 붙은 평범한 낱말 20종({@code café}·{@code jalapeño}·{@code naïve} …)에서
     * 오탐은 0건이다 — 떼어낸 결과가 사전 항목이 되는 경우가 없다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"fùck", "bìtch", "이 fùck 아", "bòzi"})
    @DisplayName("결합표시를 끼워도 막는다 — 입구 NFC 가 합치므로 평면 A 가 먼저 쪼개야 한다")
    void aCombiningMarkInsertionIsStillMasked(String evaded) {
        assertThat(filterFromDictionary().filter(evaded))
                .as("'%s' 가 통과한다 — 화면에는 악센트 하나 붙은 그 욕설이다", evaded)
                .contains("***");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"café", "jalapeño", "naïve", "Pokémon", "résumé", "señor", "façade"})
    @DisplayName("악센트가 붙은 평범한 낱말은 그대로 나간다 — Mn 을 떼도 사전 항목이 되지 않는다")
    void anAccentedOrdinaryWordIsNotMasked(String word) {
        assertThat(filterFromDictionary().filter(word))
                .as("'%s' 이(가) 가려졌다 — Mn 제거가 평범한 라틴을 사전 항목으로 만들었다", word)
                .isEqualTo(word);
    }

    /**
     * 같은 글자를 <b>다른 인코딩</b>으로 적어도 막는다.
     *
     * <p>사전의 자모 항목은 전부 호환 자모(U+3131–318E)인데 같은 글리프에 조합용 초성·중성·
     * 종성(U+1100–11FF)과 반각(U+FFA1–FFDC) 표기가 더 있다. 평면 C 가 조합용을 되돌리지만
     * 역표를 {@code NFKD} 역으로만 만들면 <b>초성형만</b> 들어온다 — 유니코드가 종성과 호환
     * 자모를 호환 분해로 잇지 않기 때문이다. 실측으로 사전의 자모 항목 73건 중 <b>72건</b>이
     * 종성 표기로, 84건 중 6건이 반각 표기로 통과했다(그 당시 분모다 — 호환 자모 항목은
     * 2026-11-04 에 83건이 됐고 두 축의 현재 통과는 모두 0 이다).
     *
     * <p>아래 벡터는 축마다 하나씩이다. 가운데에 숫자나 기호가 끼면 평면 C 가 그것을 떼어내
     * 항목이 깨지므로, 접기는 길이를 보존하는 1-패스 {@code fold} 에서 일어나야 한다.
     *
     * <p><b>자모를 전부 조합용으로 적는 축은 여기 없다.</b> 그 경우 입구 NFC 가 음절로 합쳐
     * 버리고({@code ㅅㅣㅂㅏ} → {@code 시바}) 그 음절은 사전에 없다. 실측 7건이고 합쳐진 결과가
     * {@code 보지 · 씨 · 시바 · 개자} 처럼 <b>오탐 때문에 일부러 뺀 낱말</b>이라, 닫으려면 그
     * 오탐을 다시 들여야 한다. 알고 남긴 미탐으로 {@code profanity-words.txt} 에 적어 뒀다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            // 종성형 — ㄴ1ㄱ · ㅅ1 · ㅂㅅ
            "\u11AB1\u11A8", "\u11BA1", "\u11B8\u11BA",
            // 반각 — @ㅐ미 · ^^ㅣ벌 · ㅅ1
            "@\uFFC3미", "^^\uFFDC벌", "\uFFB51",
            // 조합용 초성 — 시ㅂㅏ · 미ㅊ (자음만 조합용으로 바꿔 NFC 가 합치지 못하게 둔 형태)
            "\uC2DC\u1107\u314F", "\uBBF8\u110E",
            // 종성 + 기호 삽입. 평면 C 만이 막는 축이다 — 삽입 때문에 fold 가 1-패스로 못 닿고
            // 평면 A 는 종성 조합용을 호환으로 되돌리지 못한다. 평면 C 를 떼면 이 셋만 깨진다.
            "\uBBF81\u11BE", "\uC2DC1\u11B8\u314F", "\uC9C01\u11AF"})
    @DisplayName("조합용·반각 자모로 적은 표기도 막는다 — 역표가 초성형만이면 72/73 이 통과한다")
    void jamoWrittenInAnotherEncodingIsStillMasked(String evaded) {
        assertThat(filterFromDictionary().filter(evaded))
                .as("'%s' 가 통과한다 — 화면에는 사전에 있는 자모 그대로 보인다", evaded)
                .contains("***");
    }

    /**
     * 보호어를 접두로 붙여 욕설을 풀 수는 없다 — <b>반대 방향</b>을 못 박는다.
     *
     * <p>위 오탐을 닫을 때 화이트리스트에 긴 항목을 더했고, 덮임 판정을 "시작이 화이트 span
     * 안이면 보존" 으로 넓히자는 선택지도 있었다. 그 변형을 프로토타입으로 재 보니 자연 복합어
     * 12건이 전부 닫히는 대신 <b>욕설 복합어가 열렸다</b> — 교차 결과 125개 중 119개가 보존으로
     * 바뀌고, 새 항목 전수 스윕의 우회가 0 → 32 가 됐다. 그래서 그 변형은 쓰지 않았다.
     *
     * <p>아래 벡터가 그 결정을 지킨다. 화이트리스트 항목을 더하거나 덮임 판정을 넓히면 여기가
     * 먼저 깨진다. 끝의 {@code 감염병신병신} 은 두 방향이 동시에 성립하는 자리다 — 정당한
     * 접두({@code 감염병신})는 살고 그 뒤의 욕설만 가려야 한다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"구로역시발새끼", "시발택시발새끼", "다시팔새끼", "공개세끼",
            "시발점씨발", "시발유방만져", "시발자지빨아", "감염병신병신"})
    @DisplayName("보호어 접두로는 욕설이 풀리지 않는다 — 덮임 판정을 넓히면 119개가 보존으로 바뀐다")
    void aProtectedPrefixDoesNotUnmaskTheInsultThatFollows(String evaded) {
        assertThat(filterFromDictionary().filter(evaded))
                .as("'%s' 가 통째로 통과한다 — 보호어가 사전의 off 스위치가 됐다", evaded)
                .contains("***");
    }

    /**
     * 입구 NFC 는 미탐만 막는 것이 아니라 <b>오탐도</b> 막는다.
     *
     * <p>NFD 로 들어온 평범한 한국어는 자모로 쪼개져 있고, 매칭 평면들이 그 자모를 사전이 쓰는
     * 호환 자모로 되돌린다 — 그러면 {@code 삼촌} 안에 {@code ㅁㅊ} 이, {@code 입술} 안에
     * {@code ㅂㅅ} 이 생겨 사전 항목에 걸린다. 입구에서 음절로 합쳐 두면 되돌릴 자모가 남지
     * 않는다. 실측: 입구 NFC 를 지우면 아래 22종 중 <b>9종</b>이 검열된다(전부가 아니다 —
     * 받침이 다음 음절 초성과 사전 항목을 이루는 것만 걸린다).
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"삼촌", "입술", "없습니다", "햇볕", "합시다", "접시", "점차", "값싸다",
            "옳다", "깻잎", "앉다", "읽다", "젊다", "훑다", "닭갈비", "싫다", "밟다", "넓다",
            "짧은", "읊다", "핥다", "굶다"})
    @DisplayName("NFD 로 들어온 평범한 한국어도 그대로 나간다 — 합치지 않으면 받침이 자모 항목이 된다")
    void ordinaryKoreanWrittenInNfdIsNotMasked(String word) {
        String nfd = java.text.Normalizer.normalize(word, java.text.Normalizer.Form.NFD);
        assertThat(filterFromDictionary().filter(nfd))
                .as("'%s' 의 NFD 형이 가려졌다 — 쪼개진 받침이 자모 항목에 걸린다", word)
                .isEqualTo(word);
    }

    /**
     * 반대 방향 — 종성형이라고 다 접으면 <b>평범한 한국어</b>가 가려진다.
     *
     * <p>{@code 미}+종성 ㅊ 처럼 앞 음절에 붙을 수 있는 종성은 입구 NFC 가 {@code 및} 로
     * 합쳐 버린다. 화면도 {@code 및} 이고 뜻도 {@code 및} 이라 회피가 아니다 — 그래서 사전의
     * {@code 미ㅊ} 에 걸려선 안 된다. 실측으로 이 계급이 6건 남아 있고 전부 이 방향이 맞다
     * ({@code 및 · 질 · 잦 · 온홀 · 십ㅏ · 봊ㅣ}).
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"\uBBF8\u11BE", "\uC9C0\u11AF", "\uC790\u11BD"})
    @DisplayName("앞 음절에 붙는 종성은 접지 않는다 — 합쳐지면 다른 낱말이라 회피가 아니다")
    void aJongseongThatComposesIntoThePrecedingSyllableIsNotAnEvasion(String composed) {
        String expected = java.text.Normalizer.normalize(composed, java.text.Normalizer.Form.NFC);
        assertThat(filterFromDictionary().filter(composed))
                .as("'%s' 가 가려졌다 — 화면에는 평범한 낱말로 보인다", expected)
                .isEqualTo(expected);
    }

    /**
     * 입구에서 NFC 로 접지 않으면 평범한 한국어가 가려진다.
     *
     * <p>떼어 쓴 자모를 합치지 않고 들어오면 1-패스가 아무것도 못 찾고 2A 로 넘어가는데, 2A 는
     * 구간이 아니라 <b>토큰 전체</b>를 가린다. 실측으로 입구 NFC 를 지워 보면 {@code 병신아} 의
     * NFD 형이 {@code ***아} 가 아니라 {@code ***} 가 되고, 화이트리스트에 있는 {@code 시발점}
     * 이 {@code ***} 로 가려진다 — 보호가 평면 하나에서만 돌아 통째로 꺼진다.
     *
     * <p>그 되돌림에서 <b>이 테스트를 뺀 나머지가 전부 통과했다</b>(당시 174건). 그래서 이
     * 테스트가 따로 있다 — 수는 테스트가 늘면 변하니 기준 시점과 함께 읽을 것.
     */
    @Test
    @DisplayName("NFD 입력도 정확한 구간만 가린다 — 입구에서 안 합치면 화이트리스트가 꺼진다")
    void nfdInputIsMaskedOnTheOnePassSpanNotTheWholeToken() {
        ProfanityFilter filter = filterFromDictionary();

        String withSuffix = java.text.Normalizer.normalize("병신아", java.text.Normalizer.Form.NFD);
        assertThat(filter.filter(withSuffix))
                .as("NFD 가 토큰 전체로 번졌다 — 조사 '아' 까지 같이 가려진다")
                .isEqualTo("***아");

        String whitelisted = java.text.Normalizer.normalize("시발점", java.text.Normalizer.Form.NFD);
        assertThat(filter.filter(whitelisted))
                .as("NFD 로 쓴 '시발점' 이 가려졌다 — 화이트리스트가 1-패스 평면에만 걸려 있다")
                .isEqualTo("시발점");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ｆｕｃｋ", "ｓｉｂａｌ", "ｂｉｔｃｈ", "ＦＵＣＫ",
            // 전각 숫자 가지(FF10–19). 없으면 사전의 s1bal · 18놈 이 전각으로 통과한다.
            "ｓ１ｂａｌ", "１８놈"})
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

    /**
     * 2A 전처리가 한글과 그 밖을 다르게 접는다.
     *
     * <p>평면이 셋이고 2A·2B 가 OR 로 발화한다. 하나로 합치면 반드시 하나가 깨진다 —
     * 실제로 깨뜨렸다. 비글자 제거 평면을 코드포인트별 NFKC 로 바꿨더니 {@code ℃} 가
     * {@code °C} 로 전개되며 {@code C} 가 남아 {@code 시C발} 이 됐고, BMP 전수 차단이
     * 14,525 → 13,718 로 줄었다(829 잃고 22 얻음). ℃·℉·№·㎡ 는 라이브 커머스에 평범하게
     * 나온다.
     *
     * <p>평면 A 는 글자 아닌 것을 떼고, B 는 호환 전개 후 글자만 남기고, C 는 B 의 조합용
     * 자모를 사전이 쓰는 호환 자모로 되돌린다. 셋 다 반환 직전에 합친다 — 떼기 전에 합치면
     * 음절 안에 끼인 글자가 합성을 막는다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "시 ㅂ ㅏ", "ㅅ ㅣ ㅂ ㅏ",          // 평면 A — 글자 아닌 것을 떼면 붙는다
            "ⓕⓤⓒⓚ", "𝖋𝖚𝖈𝖐",                  // 평면 B — 호환 전개해야 글자가 나온다
            "시℃발", "시№발", "시㎡발", "시ⓐ발", // 평면 A — NFKC 가 글자를 남기면 끊긴다
            "시\uFFB2\uFFC2",                  // 평면 C — 반각은 조합용으로 접히고 사전은 호환이다
            // 평면 B — 반각 '창녀'. 사전에 'ㅊㄴ' 자모 표기가 없어 평면 C 로는 안 잡히고,
            // 호환 전개 뒤 음절로 합쳐야 걸린다. 평면 B 를 빼면 이 줄만 깨진다(실측).
            "\uFFBA\uFFC2\u11BC\uFFA4\uFFCA"})
    @DisplayName("⭐ 전처리 평면 셋이 각자 다른 계급을 잡는다 — 하나로 합치면 반드시 하나가 깨진다")
    void jamoSpreadAndCompatibilityLettersAreBothCaught(String message) {
        assertThat(filterFromDictionary().filter(message))
                .as("'%s' 이(가) 통과한다 — 평면 셋 중 하나를 희생한 것이다", message)
                .contains("***");
    }

    /**
     * 사전·설정 항목도 메시지와 같은 평면으로 접힌다.
     *
     * <p>메시지는 {@code filter()} 입구에서 NFC 로 접히는데 항목이 그대로면 둘이 영원히 만나지
     * 못한다. NFD 는 macOS 복사가 그대로 주는 형식이고, 이 두 키는 방송 중에 쓰라고 둔 통로다 —
     * 실측에서 NFD 로 쓴 단어가 거부도 경고도 없이 적재되고 <b>NFC·NFD 메시지를 둘 다</b> 못
     * 막았고, NFD 로 쓴 정당한 화이트리스트는 거짓 메시지와 함께 부팅을 세웠다.
     */
    @Test
    @DisplayName("⭐ NFD 로 쓴 설정 단어도 실제로 막는다 — 평면이 갈리면 조용히 사문이 된다")
    void aDecomposedConfiguredWordActuallyBlocks() {
        String decomposed = java.text.Normalizer.normalize("짱나죽어", java.text.Normalizer.Form.NFD);
        ProfanityDictionary dictionary = ProfanityDictionary.load(decomposed, null);
        ProfanityFilter filter = new ProfanityFilter(dictionary.words(), dictionary.whitelist());

        assertThat(filter.filter("아 짱나죽어 진짜")).contains("***");
        assertThat(filter.filter("아 " + decomposed + " 진짜")).contains("***");
    }

    @Test
    @DisplayName("⭐ NFD 로 쓴 화이트리스트도 거부되지 않는다 — 화면이 같은 값이 입구에서 갈리면 안 된다")
    void aDecomposedConfiguredWhitelistIsAccepted() {
        String decomposed = java.text.Normalizer.normalize("시발점", java.text.Normalizer.Form.NFD);

        ProfanityDictionary dictionary = ProfanityDictionary.load(null, decomposed);

        assertThat(dictionary.whitelist()).contains("시발점");
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
     * <p>한때 이 저장소의 화이트리스트 의미는 "토큰이 항목을 <b>포함</b>하면 그 토큰 전체를 보존"
     * 이었고, 그래서 항목을 하나 잘못 넣으면 그 조각을 붙인 토큰이 통째로 통과했다. 실제로 두 번
     * 밟았다 — 맨 욕설에 숫자를 붙인 {@code 8시발}, 그리고 사전에 없는 욕설을 보호하던
     * {@code 입어보지} 류 24건이다. (그 통째 보존을 구현했던 {@code isWhitelisted} 와
     * {@code AhoCorasick.containsAny} 는 지금 없다 — 과거형으로 읽을 것.)
     *
     * <p>이 잔재는 닫혔다. 한때 규칙을 지킨 항목도 전부 우회 스위치였다 — {@code 시발점씨발} 이
     * {@code 시발점} 때문에 통째로 통과했다. 마스킹을 span 단위로 바꿔(욕설 span 이 화이트리스트
     * span 에 <b>완전히 덮일 때만</b> 버린다) 당시 31건 전수에서 0건이 됐다(지금은 38건 × 사전 전수 × 양쪽 81,928케이스에서 1건 —
     * 시발유니섹스 이고, 화이트 '시발유' 가 덮는 접미 계급이다. 화이트리스트 머리글 참조). 아래
     * {@code noWhitelistEntryIsAGlobalOffSwitch} 가 그것을 고정한다.
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
            // 판정을 여기서 다시 쓰지 않는다 — 운영 경로(설정 입구)를 그대로 부른다. 전에는
            // 잔여를 이 테스트가 직접 만들었고 (가) 지우는 순서가 Set 순회라 미규정이었으며
            // (나) 판정이 '한글 음절' 로 굳어 운영이 허용하는 라틴 항목을 번들 파일에는 영원히
            // 못 넣었다. 번들 화이트리스트의 유일한 게이트가 이 테스트라 그 엄격함이 곧 계약이
            // 됐다. 이제 정의는 ProfanityDictionary 한 군데에만 있다.
            assertThatCode(() -> ProfanityDictionary.load(null, entry))
                    .as("'%s' 은(는) 설정으로 들어오면 거부될 항목이다 — 번들 파일이 설정보다 "
                            + "느슨하면 안 된다", entry)
                    .doesNotThrowAnyException();
            assertThat(words.stream().anyMatch(entry::contains))
                    .as("'%s' 은(는) 사전 단어를 품지 않는다 — 보호할 대상이 없고 우회로만 만든다", entry)
                    .isTrue();
        });
    }

    /**
     * 어떤 화이트리스트 항목도 사전 전체의 off 스위치가 아니다.
     *
     * <p>여섯 라운드 동안 "범위 밖" 으로 적혀 있던 잔재다. 항목을 접두로 붙이면 토큰이 통째로
     * 보존돼 {@code 시발점씨발} 이 통과했다 — 항목 하나하나가 사전 전체를 끄는 스위치였다.
     * 마스킹을 span 단위로 바꿔 닫았고, 여기서 전수로 고정한다.
     */
    @Test
    @DisplayName("⭐ 화이트리스트 항목에 욕설을 붙여도 통과하지 않는다 — 전수 고정")
    void noWhitelistEntryIsAGlobalOffSwitch() {
        ProfanityDictionary dictionary = ProfanityDictionary.load(null, null);
        ProfanityFilter filter = new ProfanityFilter(dictionary.words(), dictionary.whitelist());

        assertThat(dictionary.whitelist()).allSatisfy(entry -> {
            String token = entry + "씨발";
            assertThat(filter.filter(token))
                    .as("'%s' 이(가) 통째로 통과한다 — 이 항목이 사전 전체의 off 스위치다", token)
                    .isNotEqualTo(token);
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
     * 사전 최장 항목 길이 K 를 못 박는다 — <b>2B 최악 비용의 축</b>이다.
     *
     * <p>ProfanityFilter 의 2B 는 인접 2~K 토큰을 조인하므로 반복이 O(토큰수 × K) 이고 조인마다
     * 다시 K 가 붙는다. 그래서 사전에 긴 항목 하나를 더하는 것은 데이터 변경이 아니라
     * <b>지연 변경</b>이다 — 실측(2026-11-05, 199자/100토큰) K 7 / 12 / 16 / 32 / 64 에서
     * 144.7 / 296.7 / 468.6 / 1,272.0 / 2,844.4 µs, K=64 에서 352 msg/s. K 가 9.1배인데
     * 비용이 19.7배이므로 선형이 아니다. 전체 표는 {@code MAX_WORD_LENGTH} javadoc 에 있다.
     *
     * <p>SPR-165 가 그 상한을 64 에서 <b>16</b> 으로 내렸다. 이 테스트가 고정하는 K=7 은 번들
     * 데이터의 값이고 상한과 다르다 — 둘을 섞지 말 것. 상한 쪽 경계는
     * {@code seventeenCharactersIsOverBudget}·{@code sixteenCharactersIsWithinBudget} 가 잡는다.
     *
     * <p>바로 위 {@code theAsciiEntriesAreExactlyThese} 가 이 축을 <b>못 지킨다.</b> K=7 을 만드는
     * 항목은 둘인데 하나만 ASCII 다 — {@code fuckyou} 와 {@code s하고e싶다x}. ASCII 집합을 박아
     * 두어도 8음절 순한글 항목 하나를 넣으면 K 가 8이 되고 그 테스트는 초록이다. 그리고 사전
     * 추가가 실제로 일어나는 축이 그쪽이다(순한글 913/1,078).
     *
     * <p>K 는 항목 길이로 재고 fold 로 재지 않는다 — fold 는 BMP 안에서 1:1 이라 길이를 보존한다.
     */
    @Test
    @DisplayName("⭐ 사전 최장 항목은 7자다 — 이 수가 2B 최악 비용(332 msg/s)의 축이다")
    void theLongestEntryFixesTheTwoBWindow() {
        var words = ProfanityDictionary.load(null, null).words();
        int k = words.stream().mapToInt(String::length).max().orElseThrow();

        assertThat(k)
                .as("K 가 오르면 2B 반복이 O(토큰수 × K) 로 같이 오른다 — 올리려면 비용을 다시 재라")
                .isEqualTo(7);
        assertThat(words.stream().filter(w -> w.length() == k).sorted().toList())
                .as("ASCII 집합 고정은 이 축을 못 지킨다 — 둘 중 하나가 순한글 혼합이다")
                .containsExactly("fuckyou", "s하고e싶다x");
    }

    /**
     * 화이트리스트 추가가 평범한 한국어를 새로 가리지 않는다.
     *
     * <p>span 단위로 바꾸면서 생긴 새 계급이다 — 토큰 전체를 보존하지 않으므로, 보호어 뒤에
     * 붙은 조각이 사전에 걸리면 그 자리가 마스킹된다. 지금 데이터에서는 0건이지만 항목을
     * 더할 때마다 다시 재야 한다. 여기서 돌리는 것은 <b>항목 전수 × 조사 중심 22종</b>이고,
     * 그것이 이 계급의 모수가 아니다 — 뒤따르는 것은 어미도 명사도 될 수 있어 목록으로는
     * 닫히지 않는다(화이트리스트 머리글의 구조적 전수 참조). 이 테스트는 그 22종이 회귀하지
     * 않는지만 지킨다.
     *
     * <p>서로 덮는 항목을 금지하던 규칙은 걷었다. 그 근거("긴 항목은 결과를 바꾸지 못한다")가
     * 토큰 전체 보존에서만 참이었다 — span 단위에서는 긴 항목이 더 넓은 span 을 덮어 결과를
     * 바꾼다(실측: 사전 {@code cd} · 화이트 {@code abc} 면 {@code ab***}, 거기에 {@code abcd}
     * 를 더하면 {@code abcd}).
     */
    @Test
    @DisplayName("⭐ 보호어에 조사·접미사를 붙여도 새로 가려지지 않는다 — span 단위가 연 계급")
    void addingAnOrdinarySuffixToAProtectedWordDoesNotMask() {
        ProfanityDictionary dictionary = ProfanityDictionary.load(null, null);
        ProfanityFilter filter = new ProfanityFilter(dictionary.words(), dictionary.whitelist());
        String[] suffixes = {"에서", "이", "가", "로", "으로", "과정", "동", "버스", "님", "을", "를",
                "은", "는", "와", "도", "만", "까지", "부터", "보다", "처럼", "마다", "조차"};

        assertThat(dictionary.whitelist()).allSatisfy(entry -> {
            for (String suffix : suffixes) {
                String token = entry + suffix;
                assertThat(filter.filter(token))
                        .as("'%s' 이(가) 가려진다 — 보호어 뒤 조각이 사전에 걸렸다", token)
                        .isEqualTo(token);
            }
        });
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
    @DisplayName("예산을 넘는 항목은 거부한다 — 단어 파일에 산문이 섞인 것이다")
    void anOverlongEntryFailsBoot() {
        assertThatThrownBy(() -> ProfanityDictionary.load(
                "/chat/long-entry-dictionary.txt", WHITELIST, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("예산보다 길다");
    }

    /**
     * ⭐ <b>{@code normalize} 가 고정점이어야 한다 — {@code normalize(normalize(x)) == normalize(x)}.</b>
     *
     * <p>그게 깨지면 관리자 쓰기에서 <b>저장값 ≠ 집행값</b>이 된다. 쓰기 경로는 한 번 접어
     * 저장하고({@code ManageFilterWordsService.fold}) 사전 문은 받은 값에 {@code strip} 을 한 번
     * 더 걸어 판정하므로, 두 번째 접기에서 더 떨어지는 글자가 있으면 저장된 행이 문에는 다르게
     * 보인다. 실제로 선행 BOM 2개 이상에서 그랬다 — 저장 {@code <BOM>시발} · 집행 {@code 시발}.
     *
     * <p>피해가 삭제 불가다. {@code ?word=시발} 삭제는 접혀서 {@code 시발} 로 가고 DB 행은
     * {@code <BOM>시발} 이라 <b>0행</b>이며 응답은 "이미 그 상태였다" 다. 정확히 같은 2중 BOM 을
     * 다시 보내야만 맞는데, 접기가 모든 평범한 후보를 {@code 시발} 로 접으므로 <b>그 행을 가리킬
     * 수 있는 입력이 없다.</b>
     *
     * <p><b>벡터를 손으로 적지 않고 생성한다.</b> 전에 이 축의 벡터가 넷이었고
     * ({@code "시발\n"}·{@code "\n시발"}·{@code "  시발  "}·{@code "시발\t"}) 전부
     * {@code String.strip()} 이 떼는 류 하나였다 — <b>이미 찾은 버그에서만 온 벡터</b>라 BOM 축을
     * 보지 못했다. 글자 클래스 × 위치 × 반복 횟수로 돌리면 그 축이 나온다.
     */
    @Test
    @DisplayName("⭐ normalize 는 고정점이다 — 저장값과 집행값이 갈리지 않는다")
    void normalizeIsAFixedPoint() {
        // 글자 클래스: strip 이 떼는 것 · BOM(떼지만 isWhitespace 가 아님) · 둘의 조합
        String[] trimmable = {" ", "\t", "\n", "\r", "\u000B", "\f"};
        String bom = "\uFEFF";
        List<String> inputs = new ArrayList<>();
        for (int repeat = 0; repeat <= 3; repeat++) {
            String bomRun = bom.repeat(repeat);
            for (String ws : trimmable) {
                // 위치: 앞 · 뒤 · 양쪽 · BOM 사이에 공백이 끼는 모양
                inputs.add(bomRun + "시발");
                inputs.add("시발" + bomRun);
                inputs.add(bomRun + ws + "시발");
                inputs.add(ws + bomRun + "시발");
                inputs.add(bomRun + ws + bomRun + "시발");
                inputs.add(ws + bomRun + ws + "시발" + ws);
            }
        }

        // 분모를 세어 적는다 — "통과" 만 적으면 다음 사람이 이 축이 얼마나 덮였는지 모른다.
        assertThat(inputs).hasSize(144);
        assertThat(inputs).allSatisfy(input -> {
            String once = ProfanityDictionary.normalize(input);
            assertThat(ProfanityDictionary.normalize(once))
                    .as("normalize 가 고정점이 아니면 저장값과 집행값이 갈린다: %s",
                            input.replace("\uFEFF", "<BOM>"))
                    .isEqualTo(once);
        });
    }

    /**
     * ⭐ <b>예산의 경계를 양쪽에서 못 박는 두 벡터 중 거부 쪽.</b>
     *
     * <p>위 {@code anOverlongEntryFailsBoot} 는 픽스처가 70자라 상한이 16 이든 64 이든 떨어진다 —
     * 즉 그 테스트는 "거부가 존재한다" 만 재고 <b>수를 재지 않는다.</b> 상한을 64 에서 16 으로
     * 내리는 변경이 그 테스트를 초록으로 지나갔고, 그래서 경계 벡터를 따로 둔다.
     *
     * <p>이 하나만으로는 모자란다 — 상한을 1 로 내려도 초록이다. 바로 아래 통과 쪽과 둘이
     * 상한을 <b>위아래로</b> 집어야 그 수가 테스트에 박힌다.
     */
    @Test
    @DisplayName("⭐ 예산 경계 — 17자는 거부한다")
    void seventeenCharactersIsOverBudget() {
        assertThatThrownBy(() -> ProfanityDictionary.load("가".repeat(17), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("예산보다 길다")
                .as("수를 메시지에 실어야 운영자가 무엇을 줄여야 하는지 안다")
                .hasMessageContaining("상한 16자");
    }

    /** ⭐ 경계의 통과 쪽 — 이것이 없으면 상한을 더 내리는 변경이 조용히 지나간다. */
    @Test
    @DisplayName("⭐ 예산 경계 — 16자는 통과한다")
    void sixteenCharactersIsWithinBudget() {
        assertThatCode(() -> ProfanityDictionary.load("가".repeat(16), null))
                .doesNotThrowAnyException();
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

    /**
     * ⭐ 예산이 <b>NFC 평면</b>에서 재진다는 것을 못 박는다 — 깨지면 예산이 3배까지 뚫린다.
     *
     * <p>{@code reject} 가 재는 길이와 {@code ProfanityFilter} 가 K 를 세는 길이가 같은 것은
     * {@code fold} 의 성질이 아니라 <b>호출 순서</b> 덕이다: {@code reject} 는 항상
     * {@code strip()} 뒤에 불리고 {@code strip} 의 마지막 줄이 {@code normalizeEntry}(NFC) 다.
     *
     * <p>NFC 는 늘릴 수 있다. 유니코드 전수에서 코드포인트가 늘어나는 글자가 85건이고, 최악은
     * 악기 기호다 — {@code U+1D160} 하나가 NFC 에서 3 코드포인트 · UTF-16 <b>6자</b>가 된다.
     * 아래 벡터는 원문 16자(예산 안)인데 NFC 20자(예산 밖)다. 길이 검사를
     * {@code normalizeEntry} 앞으로 옮기면 이 줄이 통과하고 K 가 20 이 된다.
     *
     * <p>되돌려 재면 이 테스트만 깨진다 — 다른 길이 벡터는 전부 ASCII·한글이라 NFC 가 항등이다.
     */
    @Test
    @DisplayName("⭐ 예산은 NFC 뒤에 잰다 — 원문 16자지만 NFC 20자인 항목은 거부한다")
    void theBudgetIsMeasuredOnTheNfcPlane() {
        String widening = "가".repeat(14) + new String(Character.toChars(0x1D160));
        assertThat(widening.length())
                .as("원문 길이로 재면 예산 안이다 — 그래서 이 벡터가 순서를 가른다")
                .isEqualTo(16);

        assertThatThrownBy(() -> ProfanityDictionary.load(widening, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("예산보다 길다")
                .as("보고되는 수가 NFC 길이여야 한다 — 원문 16 을 보여 주면 운영자가 무엇을 줄일지 모른다")
                .hasMessageContaining("20자");
    }

    @Test
    @DisplayName("설정 CSV 의 예산 초과 항목도 거부한다")
    void anOverlongEntryFromConfigFailsBoot() {
        assertThatThrownBy(() -> ProfanityDictionary.load(null, "가".repeat(17)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("예산보다 길다")
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

    /**
     * 아무것도 보호하지 않는 항목은 <b>적재하고 알린다</b> — 더는 거부하지 않는다.
     *
     * <p>마스킹이 토큰 전체 보존이던 동안에는 이것이 우회로였다 — 그 조각을 붙인 토큰이 통째로
     * 통과했다. span 단위로 바꾼 뒤에는 덮을 span 이 없어 아무 일도 하지 않는다(실측:
     * {@code 감사합니다시발} 이 이 항목의 유무와 무관하게 {@code 감사합니다***}). 해롭지 않은
     * 것에 부팅을 세우지 않는다는 경계를 그대로 적용한다.
     */
    @Test
    @DisplayName("⭐ 아무것도 보호하지 않는 항목은 적재하고 알린다 — span 단위에서는 해롭지 않다")
    void aWhitelistEntryThatProtectsNothingIsLoadedAndReported() {
        ProfanityDictionary dictionary = ProfanityDictionary.load(null, "김치찌개");
        ProfanityFilter filter = new ProfanityFilter(dictionary.words(), dictionary.whitelist());

        assertThat(dictionary.whitelist()).contains("김치찌개");
        assertThat(dictionary.warnings()).anySatisfy(e -> assertThat(e).contains("김치찌개"));
        assertThat(filter.filter("김치찌개시발"))
                .as("덮을 span 이 없으면 우회로가 되지 않는다")
                .contains("***");
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
                .hasMessageContaining("글자가 남지 않는다");
    }

    /**
     * 규칙 (3) 건너뛰기가 안전한 <b>조건</b>을 고정한다 — 게이트가 접은 평면에서 판정하는 것.
     *
     * <p>"품는 욕설이 0건이면 푸는 것도 0건" 은 구조적으로 참이지만, 그 증명이 <b>접기</b>에
     * 걸려 있다. 판정을 원문 표기로 옮기면 {@code ＦＵＣＫ} 가 "욕설 0건" 으로 분류돼 건너뛰기로
     * 빠지는데, 런타임은 {@link ProfanityFilter#fold} 후 {@code fuck} 을 덮는다 — 전제가 정확히
     * 그 자리에서 깨진다. 아래 벡터는 전각·대문자가 접힌 뒤에 판정되는 것을 못 박는다.
     *
     * <p>여기서는 규칙 (1)이 먼저 잡는다(접으면 사전 단어와 <b>같은 값</b>이 된다). 어느 규칙이
     * 잡는지보다 <b>접은 뒤에 판정한다</b>가 지켜지는지가 이 테스트의 대상이다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ＦＵＣＫ", "ｆｕｃｋ", "ＳＩＢＡＬ", "ｓｉｂａｌ", "FUCK"})
    @DisplayName("⭐ 게이트는 접은 평면에서 판정한다 — 원문 표기로 옮기면 전각 항목이 사전을 푼다")
    void theGateJudgesOnTheFoldedPlane(String entry) {
        assertThatThrownBy(() -> ProfanityDictionary.load(null, entry))
                .as("'%s' 가 수락되면 런타임에서 그 욕설을 덮는다 — 접기 전에 판정한 것이다", entry)
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 번들 화이트리스트는 경고가 <b>0건</b>이어야 한다 — 설정 입구와 계약이 다르다.
     *
     * <p>규칙 (3)을 완화해 라틴 잔여 항목이 설정 입구를 통과하게 됐고, 그래서 이제 그런 항목을
     * 번들 파일에 커밋할 수도 있다. 그러면 <b>모든 파드가 매 부팅마다</b> WARN 한 줄을 낸다 —
     * 당직자에게 WARN 을 무시하도록 훈련시키는 쪽이고, 이 기능의 역사가 '조용한 실패' 였던 만큼
     * 상시 경고는 그 둘 사이에서 가장 나쁘다.
     *
     * <p>근거가 비대칭인 이유: 설정 항목은 사고 중에 타이핑되고 env 변경으로 되돌아간다. 번들
     * 항목은 리뷰를 거치지만 <b>영구히 전 파드에</b> 남는다. 리뷰를 거치니 더 느슨해도 될 것
     * 같지만 영구성 때문에 더 엄격해야 하고, 두 성질이 반대로 끌어서 결론이 "번들은 깨끗해야
     * 한다" 가 된다.
     */
    @Test
    @DisplayName("번들 화이트리스트는 기동 경고를 하나도 내지 않는다 — 상시 WARN 은 WARN 을 죽인다")
    void theBundledWhitelistProducesNoStartupWarning() {
        assertThat(ProfanityDictionary.load(null, null).warnings())
                .as("번들 항목이 경고를 내면 모든 파드가 매 부팅마다 그 줄을 낸다")
                .isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"q", "8", "茶", "!!", ".."})
    @DisplayName("욕설을 안 품은 항목은 비한글 한 글자여도 부팅을 세우지 않는다 — 푸는 것이 0건이다")
    void anEntryThatContainsNoProfanityNeverTripsTheResidueRule(String entry) {
        ProfanityDictionary dictionary = ProfanityDictionary.load(null, entry);

        // ⚠️ 축을 <메시지 문자열>로 고른다. anySatisfy 만 쓰면 다른 축의 경고가 우연히 그 토큰을
        // 품을 때 통과하므로 분모를 같이 박는다 — 이 입력이 내야 하는 경고는 정확히 하나다.
        assertThat(dictionary.warnings())
                .as("'%s' 은(는) 푸는 것이 0건인데 '싼 접두를 준다' 로 거부되고 있었다 — 그 문면이 "
                        + "그 값에 대해 거짓이다", entry)
                .hasSize(1)
                .allSatisfy(message -> assertThat(message).contains("아무것도"));
    }

    /**
     * 잔여를 보고하는 경고문이 <b>긴 항목부터 지운 결과</b>를 담는다.
     *
     * <p>당직자가 이 경고문으로 "지킬 말이 무엇인가" 를 판단하므로 잔여가 정확해야 한다.
     * {@code xsibalx} 에서 긴 {@code sibal} 을 먼저 지우면 잔여가 {@code xx} 이지만, 짧은
     * {@code siba} 를 먼저 지우면 {@code xlx} 가 된다 — 쪼개진 {@code l} 이 '남은 말' 로
     * 보고된다. 규칙 (3)의 문턱(글자 둘)도 같은 잔여를 쓰므로 순서가 판정을 바꿀 수 있다.
     */
    @Test
    @DisplayName("경고문의 잔여는 긴 항목부터 지운 결과다 — 쪼개진 글자가 섞이면 당직자가 오독한다")
    void theReportedResidueRemovesTheLongestEntryFirst() {
        ProfanityDictionary dictionary = ProfanityDictionary.load(null, "xsibalx");

        assertThat(dictionary.warnings())
                .as("잔여에 'l' 이 섞이면 짧은 항목(siba)을 먼저 지운 것이다")
                .anySatisfy(message -> assertThat(message).contains("\"xx\""));
    }

    /**
     * 긴 항목부터 지워야 잔여가 '지킬 말' 을 뜻한다.
     *
     * <p>{@code 8sibal} 의 잔여는 {@code 8} 이어야 하는데, 짧은 {@code siba} 를 먼저 지우면
     * {@code 8l} 이 되어 "글자가 남았다" 로 보인다. 실측으로 그 상태에서 {@code 8sibal} 이
     * 수락됐다 — 싼 접두를 막는 규칙이 쪼개진 글자 조각 하나에 뚫렸다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"8sibal", "8SIBAL", "1sibal", "8시발",
            // 판별자가 재는 것은 <잔여의 문자 종류>다 — 욕설의 문자 종류도, 잔여의 길이도
            // 아니다. 'x시발'(잔여 x)은 거부지만 '가시발'(잔여 가)은 수락인데 둘 다 1글자다:
            // 한글 음절은 하나로도 낱말이고 라틴 한 글자는 '8' 과 같이 싸기 때문이다. 그래서
            // 한글 욕설 + 라틴 잔여인 'x시발' 이 이 축의 벡터다.
            //
            // 위 넷은 전부 <숫자> 접두라 isLetter 가 0 이고, 문턱을 '글자 둘' 에서 '하나' 로
            // 내려도 그대로 거부된다 — 넷만으로는 문턱을 재지 못한다. 아래 셋이 그 자리를 잡는다.
            // 실측(2026-11-04, hasTwoLetters 의 >= 2 를 >= 1 로 돌연변이): ProfanityDictionaryTest
            // 328건 중 <깨지는 것은 정확히 이 셋>(xpussy · xfuck · x시발)이고 숫자 접두 넷은
            // 초록 그대로다. 집합과 날짜를 적는 이유는 전에 '320건' 만 적혀 있었고 그것이 어느
            // 집합의 수도 아니었기 때문이다(오늘 도달 가능한 수는 이 클래스 328 · chat-core 745).
            //
            // 반대 방향(한글 음절 잔여를 수락하는 쪽)은 다른 테스트들이 든다 — hasHangulSyllable
            // 항을 떼면 번들 화이트리스트 38건 중 <24건>이 규칙 (3)에 걸린다(잔여가 1음절:
            // 시발점 '점' · 전염병 '전' · 일시불 '일' · 상년도 '도' …). 여기에 '가시발' 을
            // 더하지 않는 이유가 그것이다 — 같은 보장을 24겹으로 이미 재고 있다.
            //
            // ⚠️ 그 24건이 깨지는 곳이 2026-11-04 에 <테스트에서 부팅으로> 옮겨졌다. 전에는
            // 규칙 (3)이 설정 입구에만 걸려서, 되돌림 상태에서도 파일 경로(load(null, null))가
            // 그냥 통과했다 — 번들 화이트리스트를 지키는 것이 로더가 아니라 테스트 하나뿐이었다.
            // 지금은 로더가 번들 항목도 보므로 되돌리면 적재 자체가 실패한다(실측: 이 클래스
            // 328건 중 303건 실패 — 사전을 쓰는 테스트가 전부 부팅에서 넘어진다).
            // 재는 자리가 바뀌었으므로 되돌림 확인을 할 때 <어디서 깨지는가>를 같이 볼 것:
            // '안 깨진다' 와 '테스트에서만 깨진다' 와 '부팅에서 깨진다' 는 서로 다른 보장이다.
            "xpussy", "xfuck", "x시발"})
    @DisplayName("⭐ 싼 접두는 표기가 라틴이어도 거부한다 — 잔여가 한 글자면 '8시발' 과 같은 모양이다")
    void aCheapPrefixIsRejectedInEitherScript(String entry) {
        assertThatThrownBy(() -> ProfanityDictionary.load(null, entry))
                .as("'%s' 가 수락되면 그 욕설이 한 글자 접두로 풀린다", entry)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("글자가 남지 않는다");
    }

    /**
     * 정당한 라틴 항목은 적재된다 — 한글 음절만 '말' 로 세면 방송 중에 길이 없다.
     *
     * <p>잔여를 "한글 음절" 로만 보던 동안 {@code passivation}·{@code unisex}·{@code sexual}·
     * {@code Essex}·{@code pussycat} 이 <b>전부 부팅을 세웠다</b>(완화 전 — 이 문턱과 '욕설 0건
     * 건너뛰기' 를 둘 다 되돌린 상태 — 에서 6/6 거부. 문턱만 되돌리면 1/6 이고 그 하나가
     * {@code pussycat} 이다). 그런데 라틴 항목
     * 중 영단어에 걸리는 것이 지금도 5건이라(사전 머리글 참조) 그중 하나가 방송 중에 오탐을
     * 내면 설정으로 못 막고 배포로 사전을 고쳐야 한다 — {@code sex}·{@code siva} 에서 이미 두 번
     * 그 길로 갔고 그때마다 검출을 통째로 잃었다. 그래서 잔여에 글자가 둘 이상이면 적재하고
     * {@code warnings()} 로 알린다. 한 글자({@code xpussy} → {@code x})는 여전히 거부다. {@code xsex} 는 사전에서 {@code sex} 를 뺀 뒤로
     * 품는 욕설이 0건이라 이 규칙에 들어오지 않는다 — 데이터를 예시로 쓰면 데이터와 함께 썩는다.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"passivation", "passivate", "pussycat", "unisex", "sexual", "Essex"})
    @DisplayName("⭐ 정당한 라틴 항목은 부팅을 세우지 않는다 — 방송 중에 쓸 수 있어야 한다")
    void aLegitimateLatinEntryLoads(String entry) {
        ProfanityDictionary dictionary = ProfanityDictionary.load(null, entry);

        assertThat(dictionary.whitelist())
                .as("'%s' 가 거부되면 라틴 오탐을 막을 길이 배포밖에 없다", entry)
                .contains(entry);
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
     * 자판만으로 사전을 끄는 키였다 — 사전에서 {@code sex} 를 빼면서 예외가 없어졌다.
     * 로더가 <b>예외 없이 강제하는 것은 둘</b>이다(맨 욕설 · 잔여에 한글 없음). 셋째 규칙
     * (욕설을 품을 것)은 거부가 아니라 {@code warnings()} 보고로 내려갔다 — span 단위에서는
     * 아무것도 안 덮는 항목이 아무 일도 하지 않으므로, 거부의 경계가 '해로움' 이기 때문이다.
     */
    @Test
    @DisplayName("욕설을 뺀 잔여가 한글이 아니면 거부한다 — '8시발' 은 '8' 을 붙인 우회로다")
    void aWhitelistEntryWithNoHangulResidueIsRejected() {
        assertThatThrownBy(() -> ProfanityDictionary.load(null, "8시발"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("글자가 남지 않는다");
    }

    /**
     * 화이트리스트 항목을 <b>품기만</b> 하는 단어는 정상 작동한다.
     *
     * <p>마스킹이 span 단위라 그 단어의 span 이 항목 span 보다 넓어 덮이지 않는다. 한때 이것을
     * "영원히 매치되지 않는다" 로 보고 부팅을 세웠는데 거짓이었고, 그 다음에는 거짓 경고를
     * 남겼다. 지금은 둘 다 아니다 — 그냥 작동한다.
     */
    @Test
    @DisplayName("⭐ 화이트리스트 항목을 품기만 하는 단어는 그대로 작동한다 — 경고할 것도 없다")
    void aWordMerelyContainingAWhitelistEntryWorks() {
        ProfanityDictionary dictionary = ProfanityDictionary.load("전염병같은놈", null);
        ProfanityFilter filter = new ProfanityFilter(dictionary.words(), dictionary.whitelist());

        assertThat(filter.filter("전염병같은놈")).contains("***");
        assertThat(filter.filter("아 전염병같은놈 진짜")).contains("***");
        assertThat(dictionary.warnings())
                .as("작동하는 단어에 경고를 달면 진짜 경고가 묻힌다")
                .isEmpty();
        assertThat(filter.filter("전염병 주의"))
                .as("보호어 자체는 그대로여야 한다")
                .isEqualTo("전염병 주의");
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
    @DisplayName("설정 단어가 화이트리스트 항목과 같으면 보고한다 — 그때만 정말 안 걸린다")
    void aWordIdenticalToAWhitelistEntryIsReported() {
        ProfanityDictionary dictionary = ProfanityDictionary.load("시발점", null);
        ProfanityFilter filter = new ProfanityFilter(dictionary.words(), dictionary.whitelist());

        assertThat(dictionary.warnings())
                .anySatisfy(entry -> assertThat(entry).contains("시발점"));
        assertThat(filter.filter("아 시발점 진짜"))
                .as("두 span 이 정확히 겹쳐 언제나 버려진다")
                .isEqualTo("아 시발점 진짜");
    }

    /**
     * 폭 없는 보이지 않는 글자는 <b>리소스 입구에서만</b> 부팅을 세운다.
     *
     * <p>두 입구를 다르게 두는 것이 결정이다. 리소스 실패는 배포 시점이라 방송을 끊지 않는다.
     * 설정 CSV 실패는 <b>방송 중 전 파드 기동 실패</b>이고, 죽는 것은 욕설 필터가 아니라 채팅
     * 전체(WS 세션·입장·영속)다. 그리고 이 통로는 yml.example 자신이 "방송 중에 쓰라고" 둔
     * 긴급 경로다.
     *
     * <p>이 저장소가 세운 경계는 "거부의 기준은 쓸모없음이 아니라 해로움" 인데, 폭 없는 글자가
     * 붙은 항목은 해롭지 않다 — 우회로를 열지 않고 아무것도 안 막는다. 실측으로 이모지 피커의
     * 표준형이 전부 거부였다(❤️ = U+2764 U+FE0F · 끝에 U+FE0F 가 붙은 fuck️). 그래서 설정
     * 입구는 떼고 경고한다.
     */
    @Test
    @DisplayName("⭐ 폭 없는 글자가 든 리소스 항목은 거부한다 — 배포 시점 실패라 방송을 끊지 않는다")
    void anInvisibleCharacterInAResourceFailsBoot() {
        assertThatThrownBy(() -> ProfanityDictionary.load(
                "/chat/invisible-entry-dictionary.txt", WHITELIST, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("보이지 않는 글자")
                .as("원문을 그대로 실으면 보이지 않는 글자를 지목할 수 없다")
                .hasMessageContaining("u200B")
                .as("어느 줄인지 안 알려주면 1,000줄짜리 파일에서 찾을 수 없다")
                .hasMessageContaining(":6");
    }

    @Test
    @DisplayName("⭐ 설정 CSV 의 폭 없는 글자는 떼고 경고한다 — 긴급 통로를 부팅 실패로 막지 않는다")
    void aZeroWidthCharacterInConfigIsStrippedWithAWarning() {
        ProfanityDictionary dictionary = ProfanityDictionary.load("개\u200B새끼", null);

        assertThat(dictionary.words())
                .as("떼고 적재해야 운영자가 의도한 차단이 실제로 걸린다")
                .contains("개새끼");
        assertThat(dictionary.warnings())
                .as("조용히 고치면 운영자가 자기 값이 바뀐 것을 모른다")
                .anySatisfy(entry -> assertThat(entry).contains("u200B").contains("개새끼"));
        assertThat(new ProfanityFilter(dictionary.words(), dictionary.whitelist())
                .filter("개새끼 진짜"))
                .isEqualTo("*** 진짜");
    }

    /**
     * 이모지 피커가 내보내는 표준형이 긴급 통로를 막고 있었다.
     *
     * <p>{@code ProfanityDictionary} javadoc 이 "So 를 통째로 막지는 않는다 — 그러면 이모지
     * 항목을 나중에 못 넣는다" 고 적었는데, 그 근거가 So 축에만 참이었다. 표준형은 변이
     * 선택자(U+FE0F)나 ZWJ 를 달고 오고 그 둘은 FORMAT·Mn 가지에 걸린다 — 즉 So 를 안 막아도
     * 이모지를 못 넣고 있었다.
     */
    @Test
    @DisplayName("⭐ 이모지 표준형(변이 선택자 부착)도 설정으로 들어온다 — 전에는 부팅이 실패했다")
    void anEmojiWithAVariationSelectorLoadsFromConfig() {
        ProfanityDictionary dictionary = ProfanityDictionary.load("\u2764\uFE0F", null);

        assertThat(dictionary.words())
                .as("변이 선택자만 떼면 남는 것은 평범한 1코드포인트 항목이다")
                .contains("\u2764");
        assertThat(dictionary.warnings()).anySatisfy(entry -> assertThat(entry).contains("uFE0F"));
    }

    @Test
    @DisplayName("설정 항목에 남은 공백은 그대로 거부한다 — 떼어 붙이면 운영자 의도를 추측하는 것이다")
    void anInnerSpaceInConfigStillFailsBoot() {
        assertThatThrownBy(() -> ProfanityDictionary.load("개 새끼", null))
                .as("'개 새끼' 를 '개새끼' 로 고쳐 주면 무엇을 적재했는지가 값과 달라진다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("공백");
    }

    /**
     * 보이지 않는 글자는 <b>어느 입구로도 사전 항목 안에 남지 못한다.</b>
     *
     * <p>한글 채움문자는 넷이고 전부 Lo(문자)로 분류된다 — {@code isWhitespace} 도
     * {@code isSpaceChar} 도 {@code FORMAT} 도 이들을 걸러 내지 못한다. 처음에 U+3164·U+1160
     * 둘만 막았는데, 남긴 둘이 그대로 통로였다. 셋을 막고 하나를 두는 것은 막지 않은 것과 같아서,
     * 이 테스트는 계급을 전수로 돈다.
     *
     * <p>재는 것이 "거부한다" 에서 "남지 못한다" 로 바뀌었다. 설정 CSV 입구가 폭 없는 글자를
     * <b>떼고 경고</b>하게 됐기 때문이다(긴급 통로를 부팅 실패로 막지 않기 위해 — 위
     * {@code aZeroWidthCharacterInConfigIsStrippedWithAWarning} 의 근거). 그러면 결과가 두
     * 갈래가 된다:
     * <ul>
     *   <li>공백류(U+00A0 같은 {@code isSpaceChar}) → 그대로 거부. 떼어 붙이면 운영자 의도를
     *       추측하는 것이 된다.</li>
     *   <li>폭 없는 나머지 → 제거되고 경고. 항목은 적재되고 <b>실제로 작동한다.</b></li>
     * </ul>
     * 어느 쪽이든 그 코드포인트가 사전에 들어가는 일은 없다 — 들어가면 그 항목은 적재되고도
     * 같은 글자를 품은 입력 하나만 맞는 사문이 된다. 그것이 원래 막으려던 것이고, 새 계약이
     * 그것을 더 강하게 고정한다(거부 대신 '존재하지 않음' 을 단언하므로 입구가 늘어도 유효하다).
     */
    @ParameterizedTest(name = "U+{0}")
    @ValueSource(strings = {
            "115F", "1160", "3164", "FFA0",   // 한글 채움문자 넷 — 전부 Lo 라 표준 검사에 안 걸린다
            "00A0", "200B", "2060", "FEFF",   // NBSP · ZWSP · 워드조이너 · BOM
            "FE0F", "0300",                   // 변이 선택자 · 결합 악센트 — 폭 0 인 Mn
            "E0001",                          // 태그 문자 — 보조평면이라 char 루프로는 안 잡힌다
            "2800", "FFFC"})                  // 공백처럼 렌더되는 So — 분류로는 안 잡혀 열거로 받는다
    @DisplayName("보이지 않는 글자는 사전에 남지 못한다 — 열거는 빠뜨린 하나가 통로가 된다")
    void noInvisibleCharacterSurvivesIntoTheDictionary(String codePoint) {
        String invisible = Character.toString(Integer.parseInt(codePoint, 16));
        String entry = "개" + invisible + "새끼";

        ProfanityDictionary dictionary;
        try {
            dictionary = ProfanityDictionary.load(entry, null);
        } catch (IllegalStateException rejected) {
            assertThat(rejected)
                    .as("U+%s 를 거부하면 메시지가 그 글자를 지목해야 한다", codePoint)
                    .hasMessageContaining("보이지 않는 글자");
            return;
        }
        assertThat(dictionary.words())
                .as("U+%s 가 항목 안에 남았다 — 적재되고도 그 글자를 품은 입력 하나만 맞는다", codePoint)
                .noneMatch(word -> word.contains(invisible));
        assertThat(dictionary.warnings())
                .as("U+%s 를 조용히 떼면 운영자가 자기 값이 바뀐 것을 모른다", codePoint)
                .anySatisfy(warning -> assertThat(warning).contains("폭 없는 글자를 뗐다"));
    }

    /**
     * 서로 덮는 항목을 금지하던 규칙은 걷었다 — 그 전제가 span 단위에서 거짓이다.
     *
     * <p>근거는 "긴 항목을 품은 토큰은 짧은 항목도 반드시 품으므로 결과가 바뀌지 않는다"
     * 였는데, 그것은 토큰 전체를 보존하던 동안에만 참이었다. span 단위에서는 긴 항목이
     * <b>더 넓은 span 을 덮어</b> 짧은 항목이 못 덮는 사전 매치를 추가로 보존한다. 효과가
     * 0 이 아니므로 "효과 0 인 값에 부팅을 세우지 않는다" 는 근거 자체가 성립하지 않는다.
     * 그 사실을 여기서 고정한다 — 규칙을 되살리려는 사람이 이 테스트를 먼저 보게 된다.
     */
    @Test
    @DisplayName("⭐ 더 긴 화이트리스트 항목은 결과를 바꾼다 — '중복이라 효과 0' 은 거짓이다")
    void aLongerWhitelistEntryChangesTheOutcome() {
        ProfanityFilter shortOnly = new ProfanityFilter(java.util.Set.of("cd"), java.util.Set.of("abc"));
        ProfanityFilter withLonger =
                new ProfanityFilter(java.util.Set.of("cd"), java.util.Set.of("abc", "abcd"));

        assertThat(shortOnly.filter("abcd")).isEqualTo("ab***");
        assertThat(withLonger.filter("abcd"))
                .as("긴 항목이 cd 의 span 까지 덮는다 — 중복이 아니라 다른 보호다")
                .isEqualTo("abcd");
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

    /**
     * ⭐ <b>경고 요약이 상한에서 끊기고, 그 20건이 실행마다 같다.</b>
     *
     * <p>상한이 필요한 이유는 그 줄이 ADMIN 쓰기마다 2회 · 발행마다 1회 · <b>파드마다 스냅샷
     * 교체마다 1회</b> 나가고, 상한 없이 전량이 걸리면 한 줄이 35,966자였기 때문이다.
     *
     * <p>⚠️ <b>결정론이 상한과 같이 필요하다.</b> {@code warnings()} 가 {@code Set.copyOf} 를
     * 돌려줄 때 그 집합의 순회 순서가 JVM 실행마다 달라져 상한이 <b>무작위 20건</b>을 골랐다 —
     * 경고가 21건 이상이면 파드 둘이 같은 사전에 대해 다른 20건을 찍고, 운영자가 "외 N건" 을
     * 보려고 재시작하면 또 다른 20건을 받는다. 값을 싣는 근거가 "그 행을 지목하기" 인데 21번째
     * 이후에서는 지목이 성립하지 않았다.
     *
     * <p>상한 수는 {@link ProfanityFilterFactory#LOG_ENTRY_LIMIT} 를 참조한다 — 리터럴 20 을
     * 적으면 상수를 내리는 변경에서 이 테스트만 갈린다(그 상수의 근거가 "두 자리가 같은 수를
     * 쓰게 하는 단일 소스" 인데 테스트 면 참조가 0건이었다).
     */
    @Test
    @DisplayName("⭐ 경고 요약이 상한에서 끊기고 그 선택이 실행마다 같다")
    void theWarningSummaryIsCappedAndDeterministic() {
        int limit = ProfanityFilterFactory.LOG_ENTRY_LIMIT;
        // ⚠️ 한때 여기에 같은 수의 픽스처(many)를 또 만들어 <아무 데도 먹이지 않고>
        // assertThat(many).hasSize(...) 로 자기 자신을 단언했다 — 죽은 픽스처 + 항진명제다.
        // summarize() 에 직접 먹이지 않고 warnings() 를 지난다. 직접 먹이면 순서를 버리는
        // 결함(Set.copyOf)이 이 테스트를 비껴간다 — 실측으로 변이가 통과했다.
        Set<String> whitelist = new java.util.LinkedHashSet<>();
        for (int i = 0; i < limit + 5; i++) {
            whitelist.add("정상어" + i);
        }
        Set<String> warnings = ProfanityDictionary.of("probe", Set.of("시발"), whitelist).warnings();

        // ⚠️ 표본이 <전수>여야 한다. 한때 첫 원소만 봤는데(`get(0)` 비교) 그러면 Set.copyOf 의
        // SALT 가 우연히 그 원소를 머리에 놓는 1/25 에서 변이가 생존한다 — 테스트가 실행마다
        // 흔들린다는 뜻이다. 그리고 `startsWith(List.copyOf(warnings).get(0))` 는 양변이 같은
        // 순회 순서에서 나오므로 <항진명제>였다: 순서를 아무리 망가뜨려도 통과한다.
        List<String> expected = whitelist.stream()
                .map(entry -> "화이트리스트 항목 \"" + entry + "\" — 어떤 욕설도 품지 않아 "
                        + "아무것도 보호하지 않는다")
                .toList();
        assertThat(warnings)
                .as("순서를 버리면 같은 사전에서 파드마다 다른 %d건이 찍힌다 — Set.copyOf 의 순회 "
                        + "순서는 JVM 실행마다 다르다", limit)
                .containsExactlyElementsOf(expected);

        String summary = ProfanityDictionary.summarize(warnings,
                "나머지는 chat.profanity.words · chat.profanity.whitelist 를 직접 확인");

        // ⚠️ 메시지 전체를 " · " 로 쪼개면 안 된다 — 꼬리 안내문에도 그 구분자가 들어간다.
        // 나열 부분만 센다.
        String listed = summary.substring(0, summary.indexOf(" … 외 "));
        assertThat(listed.split(" · "))
                .as("상한이 없으면 줄 길이가 경고 수만큼 자라고 그것이 모든 교체에서 나간다")
                .hasSize(limit);
        // 마지막 원소에 꼬리(… 외 N건)가 붙으므로 split 전수 비교가 아니라 접두로 본다.
        // 기대 문자열을 whitelist 에서 독립으로 만들었으므로 항진명제가 아니다.
        assertThat(summary)
                .as("보여 주는 %d건이 적재 순서의 앞 %d건이어야 지목이 재현된다", limit, limit)
                .startsWith(String.join(" · ", expected.subList(0, limit)));
        assertThat(summary).contains("외 5건").contains("직접 확인");
    }

    /**
     * ⭐ <b>공개 요약이 항목 하나의 길이도 막는다 — 개수 상한만으로는 한 줄이 안 묶인다.</b>
     *
     * <p>{@code reject} 의 {@code MAX_WORD_LENGTH} 는 {@code of()}/{@code load()} <b>안에만</b>
     * 있고 이 메서드의 공개 계약에는 없다. 그 계약이 "{@code reject} 를 안 지난 집합을 받을 수
     * 있다" 를 <b>명시로 인정하므로</b>, 개수만 막으면 한 줄이 {@code 20 × 무한}이다 — 상한이
     * 없을 때 35,966자가 나왔던 그 축이고, 제어문자 접기는 쪼개짐만 막고 길이는 안 막는다.
     */
    @Test
    @DisplayName("⭐ 공개 요약이 항목 하나의 길이도 끊는다 — 개수 상한은 한 줄을 안 묶는다")
    void thePublicSummaryTruncatesALongEntry() {
        String long1 = "화이트리스트 항목 \"" + "가".repeat(5_000) + "\" — 아무것도 보호하지 않는다";

        String summary = ProfanityDictionary.summarize(
                new java.util.LinkedHashSet<>(List.of(long1)), "직접 확인");

        assertThat(summary.length())
                .as("개수만 막으면 한 줄이 항목 길이만큼 자란다 — 그 줄이 모든 교체에서 나간다")
                .isLessThan(400);
        assertThat(summary)
                .as("자른 사실과 원래 길이를 적어야 당직자가 무엇을 못 보고 있는지 안다")
                .contains("…(" + long1.length() + "자)")
                .as("앞부분은 남아야 어느 항목인지 가늠할 수 있다")
                .contains("화이트리스트 항목");
    }

    /**
     * ⭐ <b>공개된 요약이 제어문자를 접는다 — 호출자를 묶는 규칙이 없기 때문이다.</b>
     *
     * <p>이 메서드는 지난 라운드에 {@code public} 이 됐다(소비처가 다른 모듈에 하나 더 있다).
     * 그 순간 <b>{@code reject} 를 안 지난 집합</b>을 받을 수 있는 모양이 됐고, 호출자를 묶는
     * 규칙은 없다(ArchUnit 규칙 21은 {@code install} 전용). 개행이 든 문장 하나가 로그 한 줄을
     * 여러 줄로 쪼개면 그 줄을 읽는 쪽이 어긋난다.
     *
     * <p>⚠️ <b>이 벡터는 오늘 프로덕션이 만들 수 없다</b> — 세 입구 전부 {@code reject()} 가 먼저
     * 돌고 {@code containsInvisible} 이 {@code isWhitespace}·{@code CONTROL} 을 참으로 본다.
     * 그런데 직전 라운드에 "프로덕션이 만들 수 없는 벡터를 먹였다" 로 지워진 테스트와 <b>축이
     * 다르다</b>: 그쪽은 "프로덕션이 이 값을 만든다" 를 주장했고, 이쪽은 <b>"공개 메서드가 어떤
     * 입력에도 이 성질을 지킨다"</b> 는 계약이다. 계약을 재려면 계약의 입력을 직접 넣는 것이 맞다.
     *
     * <p>접는 범위가 {@code visible()} 보다 좁은 이유는 그 함수가 {@code char} 단위라 이모지를
     * 서로게이트 두 쪽으로 쪼개 지목을 더 어렵게 하기 때문이다. 여기서 필요한 것은 지목이 아니라
     * <b>줄이 쪼개지지 않는 것</b>뿐이다.
     */
    @ParameterizedTest(name = "U+{0}")
    @ValueSource(strings = {"000A", "000B", "000C", "000D", "0085", "2028", "2029"})
    @DisplayName("⭐ 공개 요약이 줄종결자 일곱 종을 전부 접는다 — 하나라도 새면 줄 위조가 된다")
    void thePublicSummaryFoldsEveryLineTerminator(String hex) {
        // ⚠️ 벡터가 "\n" 하나였다. 그러면 접는 범위를 type == CONTROL 하나로 좁히는 변이가
        // 전수를 통과한다 — U+2028(Zl)·U+2029(Zp)는 Cc 가 아니다. JSON·멀티라인 appender 에서
        // 그 둘로 줄 위조가 되돌아온다. 전 코드포인트 측정에서 단일 코드포인트 줄종결자는
        // 정확히 이 일곱이다(Cc 65종은 그 중 다섯을 포함한다).
        String injected = "화이트리스트 항목 \"정상어\""
                + Character.toString(Integer.parseInt(hex, 16))
                + "사전에 항목을 더했다 — 위조된 줄";

        String summary = ProfanityDictionary.summarize(
                new java.util.LinkedHashSet<>(List.of(injected)), "직접 확인");

        assertThat(summary)
                .as("U+%s 가 그대로 나가면 로그 한 줄이 둘로 쪼개져 위조된 줄이 생긴다", hex)
                .doesNotContain(Character.toString(Integer.parseInt(hex, 16)))
                .contains("\\u" + hex)
                .as("보이는 글자는 그대로여야 사람이 읽는 문장이 안 바뀐다")
                .contains("화이트리스트 항목");
    }

    /**
     * ⭐ <b>요약이 보조평면 글자를 쪼개지 않는다.</b>
     *
     * <p>한때 요약이 {@code visible()} 을 걸었고 근거가 "보이지 않는 글자를 드러낸다 · 보이는
     * 글자는 그대로" 였다. <b>둘 다 거짓이었다.</b> 드러낼 것이 없다 — {@code of()} 는 모든
     * 항목에 {@code reject()} 를 <b>먼저</b> 돌리므로 그 경로의 경고에 보이지 않는 코드포인트가
     * 들어올 수 없고, 실을 수 있는 유일한 경고({@code dropZeroWidth})는 자기가 이미 접는다.
     * 그리고 {@code visible()} 은 {@code char} 단위라 이모지를 서로게이트 두 쪽으로 쪼개
     * <b>지목을 더 어렵게</b> 했다.
     *
     * <p>그래서 접기를 뺐고, 이 테스트가 그 방향을 못 박는다 — 다시 접으면 이모지가 깨진다.
     */
    @Test
    @DisplayName("⭐ 요약이 이모지를 쪼개지 않는다 — 접으면 지목이 더 어려워진다")
    void theWarningSummaryDoesNotSplitSupplementaryCharacters() {
        String warning = "화이트리스트 항목 \"시발abc😀\" — 욕설을 빼면 한글 음절이 남지 않는다";

        assertThat(ProfanityDictionary.summarize(
                        new java.util.LinkedHashSet<>(List.of(warning)), "직접 확인"))
                .as("당직자는 CSV·테이블에서 그 항목을 찾아야 한다 — 서로게이트로 쪼개면 못 찾는다")
                .isEqualTo(warning);
    }

    /**
     * ⭐ <b>절단 경계에서도 이모지를 쪼개지 않는다.</b>
     *
     * <p>⚠️ 위 테스트의 벡터가 <b>57자</b>라 {@code truncate} 가 아예 돌지 않았다 — 이름이
     * 주장하는 성질이 240 초과에서 거짓인데 그 모양을 구조적으로 볼 수 없었다.
     * {@code substring} 이 {@code char} 경계라 경계에 이모지가 걸리면 짝 없는 서로게이트가
     * 나간다.
     *
     * <p>프로덕션에서 도달한다: 설정 CSV {@code "😀시발" + ZWSP×28} → {@code dropZeroWidth} 가
     * 333자 경고를 만들고 그 경계에 서로게이트가 놓인다. 그리고 <b>{@code visible()} 을 뺀 근거가
     * 정확히 이 축</b>이었다 — 바로 아래 새 코드가 같은 축을 다시 연 모양이었다.
     */
    @Test
    @DisplayName("⭐ 절단 경계에 이모지가 걸려도 쪼개지지 않는다 — 벡터가 57자라 안 돌던 자리다")
    void truncationDoesNotSplitSupplementaryCharactersAtTheBoundary() {
        String warning = "가".repeat(239) + "\uD83D\uDE00" + "꼬리".repeat(60);

        String summary = ProfanityDictionary.summarize(
                new java.util.LinkedHashSet<>(List.of(warning)), "직접 확인");

        // ⚠️ 단위가 <코드포인트>다. 절단이 코드포인트 경계인데 꼬리의 수가 UTF-16 char 였고,
        // 이모지가 섞이면 운영자가 세는 수와 두 배까지 벌어졌다. 그 단위를 여기서 핀으로 박는다 —
        // char 로 되돌리는 변이가 이 단언에서 깨진다.
        int codePoints = warning.codePointCount(0, warning.length());
        assertThat(codePoints)
                .as("벡터가 두 단위를 실제로 가르는지 먼저 확인한다 — 같으면 이 핀이 아무것도 안 잰다")
                .isNotEqualTo(warning.length());
        assertThat(summary)
                .as("절단이 일어나야 이 테스트가 무언가를 잰다")
                .contains("…(" + codePoints + "자)");
        // 짝이 맞는 서로게이트는 codePoints() 에서 하나의 보조평면 코드포인트로 합쳐지고,
        // <짝 없는> 쪽만 D800..DFFF 범위의 코드포인트로 남는다. 그 0 을 단언한다.
        assertThat(summary.codePoints().filter(cp -> cp >= 0xD800 && cp <= 0xDFFF).count())
                .as("짝 없는 서로게이트가 나가면 당직자가 CSV 에서 그 항목을 못 찾는다")
                .isZero();
    }

    /**
     * 도달 가능한 경고가 실제로 어떤 모양인지 못 박는다 — <b>프로덕션이 만들 수 있는 값</b>으로.
     *
     * <p>지운 테스트는 {@code summarize} 에 손으로 만든 {@code "… \"시발<BOM>\" …"} 을 먹였다.
     * 프로덕션이 만들 수 없는 모양이고, 값을 단언하는 테스트가 프로덕션이 그 값을 만든다는
     * 증거는 아니다.
     */
    @Test
    @DisplayName("도달 가능한 경고에는 보이지 않는 글자가 없다 — 문이 먼저 거부한다")
    void aReachableWarningCarriesNoInvisibleCodePoint() {
        Set<String> warnings =
                ProfanityDictionary.of("probe", Set.of("시발"), Set.of("시발abc")).warnings();

        assertThat(warnings).isNotEmpty();
        assertThat(ProfanityDictionary.summarize(warnings, "직접 확인"))
                .as("of() 가 reject() 를 먼저 돌리므로 이 경로의 경고에는 들어올 수 없다")
                .doesNotContain("\\u");
    }
}
