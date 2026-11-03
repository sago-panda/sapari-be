package com.sapari.chat.domain.rule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * 욕설 마스킹 필터(순수·불변). 사전·화이트리스트를 생성자로 주입받아 in-memory로 검사한다.
 * 단어 출처(.txt/DB 등)·갱신은 외부 관심사 — 이 클래스는 단어 집합만 받고 정규화는 내부에서 소유한다.
 *
 * <p>3-스텝(우회 종류별 분리). 마스킹은 <b>우회가 일어난 토큰/윈도우 범위만</b> 정밀하게 {@code ***}로 가리고
 * 나머지 정상어는 보존한다:
 * <ol>
 *   <li>1-패스: 원문 직접 매칭 → 해당 욕설 span만 {@code ***}(위치 보존).
 *   <li>2A: 토큰 내부 특수문자·숫자 제거가 <b>원문엔 없던</b> 욕설을 드러낸 경우 → 그 토큰을 {@code ***}.
 *   <li>2B: 인접 토큰(2~K개)을 2A 전처리 후 조인한 결과가 정확히 욕설이면 → 그 윈도우(토큰 범위)를 {@code ***}.
 * </ol>
 * 화이트리스트는 1-패스에서 <b>리터럴 토큰</b>에만 적용한다(정상어 보호). 2A·2B는 우회로 판단된 토큰만 가리므로
 * 보정이 없다 — 즉 사전적 단어 `시발점`은 보존하되, 일부러 끼워넣은 `시1발점`은 우회로 보고 마스킹한다.
 * <p><b>입력 정규화.</b> 매칭 전에 NFC 로 접고, 대소문자와 전각 라틴·숫자를 {@link #fold} 로
 * 접는다. 접지 않으면 화면에 똑같이 보이는 입력이 사전을 통째로 지나간다 — NFD 분해형과
 * 전각({@code ｆｕｃｋ})이 그랬다. 호환 분해(½ · ﬁ · ㈜)는 접지 않는다. 길이가 1:1 이 아니라
 * span 슬라이싱 계약이 깨지고, 결과가 영속되는 본문이라 판매자 문구를 고쳐 쓰게 된다.
 * 음절을 띄어 쓴 자모 분산(예: {@code 시 ㅂ ㅏ})은 2B 가 인접 토큰을 조인해 잡는다 — 사전에
 * {@code 시ㅂㅏ}·{@code ㅅㅣㅂㅏ} 가 리터럴로 있다. 범위 밖이라고 적었던 적이 있는데 거짓이었다.
 *
 * <p>2A·2B 전처리는 한글과 그 밖을 다르게 접는다. 한글은 NFC 까지만 — NFKC 는 호환 자모
 * {@code ㅂ}(U+3142)를 조합용 자모로 바꿔 사전의 {@code 시ㅂㅏ} 류 항목과 어긋나게 만든다.
 * 그 밖은 코드포인트마다 NFKC 라 원문자 {@code ⓕⓤⓒⓚ} 와 수학 알파벳 {@code 𝖋𝖚𝖈𝖐} 이 닫힌다.
 * 거기서는 토큰 전체를 가리므로 길이가 바뀌어도 되고, 반환값은 원문에서 잘라 만든다.
 * 1-패스는 여전히 길이 보존 {@link #fold} 만 쓴다.
 *
 * <p><b>동형자는 범위 밖이다.</b> 키릴 {@code с}(U+0441) 같은 글자는 라틴과 다른 글자이고
 * 어떤 정규화도 접지 않는다 — {@code fuсk} 는 지금 통과한다. 닫으려면 유니코드 confusables
 * 표가 필요하고 그건 새 데이터 의존이다. 한글 항목은 이 축의 영향을 받지 않으므로 노출은
 * 라틴 항목에 유계다.
 *
 * <p><b>보이지 않는 글자.</b> 대부분은 글자가 아니라 2A 전처리가 떼어 낸다. 예외가 한글
 * 채움문자 넷(U+115F·U+1160·U+3164·U+FFA0)이고 유니코드가 이들을 '문자'(Lo)로 분류해
 * 전처리를 그냥 지나갔다 — 한 글자로 사전 전체가 꺼졌다. {@link #isHangulFiller} 가 그 자리다.
 */
public final class ProfanityFilter {

    private static final String MASK = "***";
    private static final Pattern TOKEN = Pattern.compile("\\S+");

    private final AhoCorasick profanity;        // 1-패스 span · 2A 부분 매칭
    private final AhoCorasick whitelistMatcher; // 1-패스 토큰이 화이트리스트를 포함하는지
    private final Set<String> profanitySet;     // 2B 조인 결과 정확 매칭(소문자)
    private final int maxWindow;                // K = 사전 최장 욕설 음절 수

    public ProfanityFilter(Set<String> words, Set<String> whitelist) {
        Set<String> lowerWords = new HashSet<>();
        int max = 0;
        for (String w : words) {
            if (w != null && !w.isBlank()) {
                String lw = fold(w);
                lowerWords.add(lw);
                max = Math.max(max, lw.length());
            }
        }
        Set<String> lowerWhite = new HashSet<>();
        for (String w : whitelist) {
            if (w != null && !w.isBlank()) {
                lowerWhite.add(fold(w));
            }
        }
        this.profanity = new AhoCorasick(lowerWords);
        this.whitelistMatcher = new AhoCorasick(lowerWhite);
        this.profanitySet = lowerWords;
        this.maxWindow = max;
    }

    /** message의 욕설을 마스킹한 노출용 본문을 반환. null·공백 메시지는 그대로 돌려준다. */
    public String filter(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        // NFC 로 접고 시작한다. 안 접으면 NFD 입력이 사전 전체를 통과한다 — 화면에는 똑같이
        // 보이고 자모가 따로 저장될 뿐이라, 보낸 사람 말고는 아무도 구별하지 못한다.
        // NFKC 가 아니라 NFC 인 것이 중요하다. 이 결과가 displayMessage 로 영속되는데 NFKC 는
        // 판매자 문구를 고쳐 쓴다(실측: ㈜사파리 → (주)사파리 · Ⅲ단계 → III단계 · ① → 1).
        // NFC 는 NFD 와 화면이 같고 평범한 입력에 대해 왕복이 항등이다(실측).
        String normalized = Normalizer.isNormalized(message, Normalizer.Form.NFC)
                ? message
                : Normalizer.normalize(message, Normalizer.Form.NFC);
        return maskNormalized(normalized);
    }

    private String maskNormalized(String message) {
        List<int[]> tokens = tokenSpans(message);
        String[] stripped = new String[tokens.size()];
        for (int i = 0; i < tokens.size(); i++) {
            stripped[i] = stripNonLetters(token(message, tokens, i));
        }

        // 2A/2B로 "우회"라 판단된 원문 char 범위 — 해당 토큰/윈도우 전체를 *** 한다.
        List<int[]> evasions = evasionRanges(message, tokens, stripped);
        List<int[]> merged = mergeRanges(evasions);

        // 우회 범위는 *** 로, 그 밖의 구간은 1-패스 부분 마스킹으로 채운다(정상어·공백 보존).
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (int[] range : merged) {
            out.append(partialMask(message.substring(cursor, range[0])));
            out.append(MASK);
            cursor = range[1];
        }
        out.append(partialMask(message.substring(cursor)));
        return out.toString();
    }

    private List<int[]> evasionRanges(String message, List<int[]> tokens, String[] stripped) {
        List<int[]> ranges = new ArrayList<>();
        // 2A: 전처리가 "원문엔 없던" 욕설을 드러낸 토큰(원문에 이미 있으면 1-패스가 부분 처리 — 예: "시발!").
        for (int i = 0; i < tokens.size(); i++) {
            String t = token(message, tokens, i);
            // 전처리(특수문자·숫자 제거)가 "원문보다 더 많은" 욕설을 드러냈을 때만 = 우회가 실제로 숨어 있던 경우.
            // 욕설 수가 그대로면 단순 구두점일 뿐이라 1-패스가 부분 처리한다(예: "시발!" → "***!").
            if (!stripped[i].equals(t)
                    && profanity.findAll(fold(stripped[i])).size()
                        > profanity.findAll(fold(t)).size()) {
                ranges.add(new int[]{tokens.get(i)[0], tokens.get(i)[1]});
            }
        }
        // 2B: 인접 2~K 토큰의 2A 전처리 결과가 정확히 욕설이면(완전 소비) 그 윈도우 범위.
        for (int w = 2; w <= maxWindow && w <= tokens.size(); w++) {
            for (int start = 0; start + w <= tokens.size(); start++) {
                StringBuilder joined = new StringBuilder();
                for (int k = start; k < start + w; k++) {
                    joined.append(stripped[k]);
                }
                if (profanitySet.contains(fold(joined.toString()))) {
                    ranges.add(new int[]{tokens.get(start)[0], tokens.get(start + w - 1)[1]});
                }
            }
        }
        return ranges;
    }

    // 1-패스 부분 마스킹 — 구간 내 토큰별로 욕설 span만 ***, 화이트리스트 토큰은 보존. 공백 그대로 유지.
    private String partialMask(String region) {
        Matcher m = TOKEN.matcher(region);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(region, last, m.start());
            out.append(maskToken(m.group()));
            last = m.end();
        }
        out.append(region, last, region.length());
        return out.toString();
    }

    private String maskToken(String token) {
        if (isWhitelisted(token)) {
            return token;
        }
        List<int[]> spans = profanity.findAll(fold(token));
        if (spans.isEmpty()) {
            return token;
        }
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (int[] span : mergeRanges(spans)) {
            out.append(token, cursor, span[0]).append(MASK);
            cursor = span[1];
        }
        out.append(token, cursor, token.length());
        return out.toString();
    }

    // 겹치거나 맞닿은 범위를 하나로 합친다.
    private List<int[]> mergeRanges(List<int[]> ranges) {
        ranges.sort(Comparator.comparingInt(a -> a[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] range : ranges) {
            if (!merged.isEmpty() && range[0] <= merged.get(merged.size() - 1)[1]) {
                int[] prev = merged.get(merged.size() - 1);
                prev[1] = Math.max(prev[1], range[1]);
            } else {
                merged.add(new int[]{range[0], range[1]});
            }
        }
        return merged;
    }

    private List<int[]> tokenSpans(String message) {
        List<int[]> spans = new ArrayList<>();
        Matcher m = TOKEN.matcher(message);
        while (m.find()) {
            spans.add(new int[]{m.start(), m.end()});
        }
        return spans;
    }

    private String token(String message, List<int[]> tokens, int i) {
        return message.substring(tokens.get(i)[0], tokens.get(i)[1]);
    }

    private boolean isWhitelisted(String s) {
        return whitelistMatcher.containsAny(fold(s));
    }

    /**
     * 매칭 평면으로 접는다 — <b>길이를 보존한다.</b>
     *
     * <p>접는 것은 둘이다. ASCII 대문자(A–Z)와 전각 라틴·숫자(U+FF10–FF19 · U+FF21–FF3A ·
     * U+FF41–FF5A). 전각을 안 접으면 {@code ｆｕｃｋ} 가 사전을 그대로 지나간다 — 화면에 보이는
     * 글자는 같고 코드포인트만 다르다. 전각 라틴은 ASCII 와 1:1 이라 접어도 길이가 그대로다.
     *
     * <p>길이 보존이 계약인 이유: {@code maskToken} 이 여기서 얻은 span 인덱스로 <b>원문</b>을
     * 자른다. {@code String.toLowerCase()} 는 로케일에 따라 길이가 변하고('İ' → "i̇" 2글자)
     * 그러면 슬라이싱이 StringIndexOutOfBounds 로 터진다. 같은 이유로 ½ · ﬁ 같은 호환 분해는
     * 여기서 접지 않는다 — 그쪽은 1:1 이 아니다.
     */
    public static String fold(String s) {
        char[] cs = s.toCharArray();
        for (int i = 0; i < cs.length; i++) {
            char c = cs[i];
            if (c >= 'A' && c <= 'Z') {
                cs[i] = (char) (c + 32);
            } else if (c >= '\uFF21' && c <= '\uFF3A') {
                cs[i] = (char) (c - 0xFF21 + 'a');
            } else if (c >= '\uFF41' && c <= '\uFF5A') {
                cs[i] = (char) (c - 0xFF41 + 'a');
            } else if (c >= '\uFF10' && c <= '\uFF19') {
                cs[i] = (char) (c - 0xFF10 + '0');
            }
        }
        return new String(cs);
    }

    // 토큰 내부 특수문자·숫자 제거(글자만 남김) — 2A·2B 전처리.
    /**
     * 2A 전처리 — 글자가 아닌 것을 떼어 "원문엔 없던 욕설" 이 드러나는지 본다.
     *
     * <p>한글 채움문자 넷은 {@code Character.isLetter} 가 <b>true</b> 로 보기 때문에(Lo) 여기서
     * 따로 떼어 내야 한다. 떼지 않으면 그 한 글자가 사전 전체를 무력화한다 — 토큰 정규식
     * {@code \S+} 도 구분자로 보지 않으므로 {@code 시<U+3164>발} 은 토큰 하나이고, 전처리가
     * 그대로 두면 {@code 시발} 이 만들어지지 않아 2A 가 발화하지 않는다. 화면에는 공백으로
     * 보이므로 보낸 사람 말고는 아무도 눈치채지 못한다(실측: 다른 보이지 않는 글자는 전부
     * 글자가 아니라 이미 떨어졌고, 정확히 이 넷만 통과했다).
     */
    private String stripNonLetters(String token) {
        // 한글과 그 밖을 다르게 접는다. 2A·2B 는 토큰 전체를 가리므로 길이가 바뀌어도 되고,
        // filter() 의 반환값은 원문에서 잘라 만들므로 ㈜사파리 · Ⅲ단계 는 그대로 나간다.
        //
        // 한글: NFC 로 합치기만 한다. NFKC 를 쓰면 호환 자모(ㅂ U+3142)가 조합용 자모(U+1107)로
        // 바뀌어 사전의 '시ㅂㅏ' 류 항목과 더는 같지 않다 — 한 번 그렇게 깨뜨렸다.
        // 그 밖: 코드포인트마다 NFKC. 원문자(ⓕ, So)와 수학 알파벳(𝖋, 보조평면 Ll)이 그걸로
        // 'f' 가 된다. 둘 다 접지 않으면 사전의 라틴 항목을 통째로 지나간다.
        String composed = Normalizer.normalize(token, Normalizer.Form.NFC);
        StringBuilder sb = new StringBuilder(composed.length());
        composed.codePoints().forEach(cp -> {
            if (isHangulFiller(cp)) {
                return;
            }
            if (isHangul(cp)) {
                if (Character.isLetter(cp)) {
                    sb.appendCodePoint(cp);
                }
                return;
            }
            String folded = Normalizer.normalize(Character.toString(cp), Normalizer.Form.NFKC);
            folded.codePoints().filter(Character::isLetter).forEach(sb::appendCodePoint);
        });
        return sb.toString();
    }

    /** 한글 음절·자모 영역. 이 안에서는 NFKC 가 사전 표기를 바꿔 버리므로 NFC 까지만 접는다. */
    private static boolean isHangul(int cp) {
        return (cp >= 0x1100 && cp <= 0x11FF)      // 조합용 자모
                || (cp >= 0x3130 && cp <= 0x318F)  // 호환 자모
                || (cp >= 0xA960 && cp <= 0xA97F)  // 확장 A
                || (cp >= 0xAC00 && cp <= 0xD7FB); // 음절 + 확장 B
    }

    /**
     * 한글 채움문자 넷. 폭이 0 이거나 공백처럼 보이는데 유니코드가 '문자'(Lo)로 분류한다.
     *
     * <p>여기가 단일 출처다 — 사전 적재 쪽({@code ProfanityDictionary})도 이 판정을 쓴다.
     * 두 평면이 갈리면 한쪽이 막는 것을 다른 쪽이 통과시킨다.
     */
    public static boolean isHangulFiller(int codePoint) {
        return codePoint == 0x115F || codePoint == 0x1160
                || codePoint == 0x3164 || codePoint == 0xFFA0;
    }
}
