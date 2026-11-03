package com.sapari.chat.domain.rule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;
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
 * <p>반각 한글 자모(U+FFA1–FFDC)와 원문자·괄호 한글(U+3200–321F · U+3260–327F)은 한글 범위
 * <b>밖</b>이라 호환 전개를 거친다 — 그 결과가 조합용 자모라서 평면 C 가 사전의 호환 자모로
 * 되돌린다. 사전의 자모 표기 84건 중 반각으로 바꿔 통과하는 것이 83 → 6 이 된 자리다.
 *
 * <p>그 마지막 6건과 종성형 표기는 평면 C 가 아니라 {@link #fold} 가 닫는다. 평면 C 는 글자
 * 아닌 것을 떼어내므로 {@code ㄴ1ㄱ}·{@code @ㅐ미} 처럼 가운데에 숫자나 기호가 낀 항목이
 * 깨지고, 종성형(U+11A8–11C2)은 애초에 역표에 없었다({@code NFKD} 가 초성형만 준다).
 * 실측: 종성 표기로 자모 항목 73건 중 <b>72건</b>, 반각으로 84건 중 6건이 통과했는데 지금은
 * 양쪽 다 0 이다.
 *
 * <p><b>자모를 전부 조합용으로 적는 축은 남아 있다 — 7건.</b> 그 경우 입구 NFC 가 음절로
 * 합쳐 버리고({@code ㅅㅣㅂㅏ} → {@code 시바}) 그 음절은 사전에 없다. 합쳐진 결과가
 * {@code 보지 · 씨 · 시바 · 개자} 처럼 <b>오탐 때문에 일부러 뺀 낱말</b>이라, 사전에 넣어
 * 닫으면 그 오탐이 돌아온다. 반대로 앞 음절에 붙는 종성(예: {@code 미}+종성 ㅊ → {@code 및})은
 * 회피가 아니다 — 화면도 뜻도 다른 낱말이 되므로 접지 않는 것이 맞고, 그쪽이 6건이다.
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
    private static final String COMPATIBILITY_NAME_PREFIX = "HANGUL LETTER ";
    private static final Pattern VARIANT_NAME_PREFIX =
            Pattern.compile("^(HANGUL (CHOSEONG|JUNGSEONG|JONGSEONG)|HALFWIDTH HANGUL LETTER) ");
    private static final Map<Integer, Integer> TO_COMPATIBILITY_JAMO = buildJamoTable();
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
        // 그리고 이것은 미탐만 막는 게 아니다 — 여기서 합쳐 두지 않으면 아래 평면들이 쪼개진
        // 받침을 호환 자모로 되돌려 '삼촌' 안에 ㅁㅊ, '입술' 안에 ㅂㅅ 을 만든다. 실측으로
        // 이 줄을 지우면 평범한 한국어 22종 중 9종이 검열된다. 순서를 뒤집지 말 것.
        String normalized = Normalizer.isNormalized(message, Normalizer.Form.NFC)
                ? message
                : Normalizer.normalize(message, Normalizer.Form.NFC);
        return maskNormalized(normalized);
    }

    private String maskNormalized(String message) {
        List<int[]> tokens = tokenSpans(message);
        // 전처리 평면 셋. 하나로는 세 방향을 다 못 가진다 — 아래 세 메서드의 javadoc 참조.
        String[] plain = new String[tokens.size()];
        String[] compat = new String[tokens.size()];
        String[] jamo = new String[tokens.size()];
        for (int i = 0; i < tokens.size(); i++) {
            String t = token(message, tokens, i);
            plain[i] = stripToLetters(t);
            // 평면 C 는 합성 전에서 파생해야 한다 — 합친 뒤에는 조합용 자모가 이미
            // 음절로 사라져 되돌릴 것이 없다.
            String expanded = expandToLetters(t);
            compat[i] = compose(expanded);
            jamo[i] = toCompatibilityJamo(expanded);
        }

        // 2A/2B로 "우회"라 판단된 원문 char 범위 — 해당 토큰/윈도우 전체를 *** 한다.
        List<int[]> evasions = evasionRanges(message, tokens, plain, compat, jamo);
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

    private List<int[]> evasionRanges(String message, List<int[]> tokens,
            String[] plain, String[] compat, String[] jamo) {
        List<int[]> ranges = new ArrayList<>();
        // 2A: 전처리가 "원문엔 없던" 욕설을 드러낸 토큰(원문에 이미 있으면 1-패스가 부분 처리 — 예: "시발!").
        // 두 평면 중 하나라도 드러내면 우회로 본다.
        for (int i = 0; i < tokens.size(); i++) {
            String t = token(message, tokens, i);
            if (revealsMore(plain[i], t) || revealsMore(compat[i], t)
                    || revealsMore(jamo[i], t)) {
                ranges.add(new int[]{tokens.get(i)[0], tokens.get(i)[1]});
            }
        }
        // 2B: 인접 2~K 토큰의 2A 전처리 결과가 정확히 욕설이면(완전 소비) 그 윈도우 범위.
        for (int w = 2; w <= maxWindow && w <= tokens.size(); w++) {
            for (int start = 0; start + w <= tokens.size(); start++) {
                if (joinedIsProfanity(plain, start, w) || joinedIsProfanity(compat, start, w)
                        || joinedIsProfanity(jamo, start, w)) {
                    ranges.add(new int[]{tokens.get(start)[0], tokens.get(start + w - 1)[1]});
                }
            }
        }
        return ranges;
    }

    // 전처리가 "원문보다 더 많은" 욕설을 드러냈을 때만 = 우회가 실제로 숨어 있던 경우.
    // 욕설 수가 그대로면 단순 구두점일 뿐이라 1-패스가 부분 처리한다(예: "시발!" → "***!").
    private boolean revealsMore(String stripped, String token) {
        return !stripped.equals(token)
                && profanity.findAll(fold(stripped)).size() > profanity.findAll(fold(token)).size();
    }

    // 이어 붙인 뒤에 한 번 더 합친다. 토큰별로만 합치면 경계에서 쪼개진 자모가 안 붙는다 —
    // NFD 로 보낸 음절 가운데에 공백을 넣으면 'ᄉ' 과 'ᅵ발' 두 토큰이 되고, 각각은 더 합칠 것이
    // 없어 그대로 남는다. 붙인 다음 합쳐야 '시발' 이 된다(실측: 그 세 건이 그 모양이었다).
    private boolean joinedIsProfanity(String[] stripped, int start, int window) {
        StringBuilder joined = new StringBuilder();
        for (int k = start; k < start + window; k++) {
            joined.append(stripped[k]);
        }
        return profanitySet.contains(fold(compose(joined.toString())));
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
        String folded = fold(token);
        List<int[]> spans = uncovered(profanity.findAll(folded), whitelistMatcher.findAll(folded));
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

    /**
     * 화이트리스트 span 에 <b>완전히 덮인</b> 욕설 span 을 버린다.
     *
     * <p>전에는 "토큰이 화이트리스트 항목을 포함하면 토큰 전체를 보존" 이었다. 그러면 항목
     * 하나하나가 사전 전체의 off 스위치가 된다 — {@code 시발점씨발} 은 {@code 시발점} 때문에
     * 통째로 통과했다. 보호해야 하는 것은 <b>그 정상어가 차지한 자리</b>뿐이지 토큰 전부가
     * 아니다.
     *
     * <pre>
     * 시발점     시발[0,2) ⊂ 시발점[0,3)              → 버림 → 마스킹 0
     * 시발점씨발  시발[0,2) ⊂ 시발점[0,3) 버림 · 씨발[3,5) 남음 → 시발점***
     * </pre>
     *
     * <p>포함은 <b>완전 포함</b>이다. 걸쳐 있기만 한 것은 남긴다 — 정상어의 꼬리에 욕설이
     * 붙은 모양이고, 그건 보호 대상이 아니다.
     *
     * <p><b>거르기를 병합보다 먼저</b> 한다. 순서가 결과를 바꾼다 — 사전 {@code {abc, cde}} ·
     * 화이트 {@code {xabc}} · 입력 {@code xabcde} 에서 거르고 병합하면 {@code xab***} 이지만
     * 병합하고 거르면 합쳐진 범위가 어느 화이트 span 에도 안 들어가 {@code x***} 가 된다.
     * 덮인 자리를 지키는 쪽이 이 함수의 목적이므로 거르기가 먼저다. {@code mergeRanges} 를
     * 앞으로 옮기지 말 것.
     *
     * <p><b>부분 겹침에서는 어느 순서도 깨끗하지 않다.</b> 실례 —
     * {@code 전염병} 뒤에 {@code ㅅㅂ} 을 붙이면 사전 항목 {@code 병ㅅ} 의 span [2,4) 와
     * {@code ㅅㅂ} 의 [3,5) 가 병합돼 [2,5) 가 되고, 보호어의 {@code 병} 까지 같이 가려
     * {@code 전염***} 이 된다. 우회 시도를 막는 것 자체는 맞아서 그대로 둔다 — 완전 포함만
     * 보존하는 이 규칙의 대가이고, 이 값을 "버그" 로 보고 걸친 span 까지 버리면 정상어 꼬리에
     * 욕설을 붙이는 우회가 열린다. 호환 자모로 적어도 같은 값이 나온다(인코딩과 무관하다).
     */
    private static List<int[]> uncovered(List<int[]> spans, List<int[]> whitelistSpans) {
        List<int[]> kept = new ArrayList<>(spans.size());
        for (int[] span : spans) {
            boolean covered = false;
            for (int[] white : whitelistSpans) {
                if (white[0] <= span[0] && span[1] <= white[1]) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                kept.add(span);
            }
        }
        return kept;
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
            } else if ((c >= '\u1100' && c <= '\u11FF') || (c >= '\uFFA1' && c <= '\uFFDC')) {
                // 홀로 남은 조합용·반각 자모. 입구 NFC 가 합칠 수 있는 것은 이미 음절이 됐으니
                // 여기 오는 것은 앞 모음이 없어 합쳐지지 못한 것뿐이고, 화면에는 호환 자모와
                // 같은 글리프로 보인다. 사전은 호환 자모로 적혀 있어 접어야 1-패스가 본다.
                // 평면 C 가 아니라 여기여야 하는 이유: 평면 C 는 글자 아닌 것을 떼어내므로
                // 가운데에 숫자나 기호가 끼면 항목이 깨진다(실측: 이 분기가 없으면 ㄴ1ㄱ ·
                // ㄴ1ㅁ · ㅅ1 · @ㅐ미 · @ㅐ비 · ^^ㅣ벌 이 종성·반각 표기로 통과한다).
                // 1-패스는 길이를 보존해야 하는데 이 표는 BMP 한 글자 → 한 글자다.
                Integer compatibility = TO_COMPATIBILITY_JAMO.get((int) c);
                if (compatibility != null) {
                    cs[i] = (char) compatibility.intValue();
                }
            }
        }
        return new String(cs);
    }

    // 토큰 내부 특수문자·숫자 제거(글자만 남김) — 2A·2B 전처리.
    /**
     * 평면 A — 글자가 아닌 것을 <b>떼어 낸다.</b>
     *
     * <p>{@code 시℃발} 처럼 기호를 끼운 우회를 이쪽이 잡는다. 떼고 나면 음절이 붙어
     * {@code 시발} 이 된다.
     *
     * <p>한때 이 평면을 코드포인트별 NFKC 로 바꿨다가 회귀를 만들었다 — NFKC 가 전개하면서
     * <b>글자를 남기면</b> 인접성이 깨진다({@code ℃} → {@code °C} 의 {@code C} 가 살아남아
     * {@code 시C발}). BMP 전수에서 차단이 14,525 → 13,718 로 줄었다. ℃·℉·№·㎡ 는 라이브
     * 커머스에 평범하게 나오는 글자다. 그래서 두 평면을 따로 두고 둘 중 하나라도 드러내면
     * 우회로 본다.
     */
    private String stripToLetters(String token) {
        StringBuilder sb = new StringBuilder(token.length());
        token.codePoints().forEach(cp -> {
            if (Character.isLetter(cp) && !isHangulFiller(cp)) {
                sb.appendCodePoint(cp);
            }
        });
        return compose(sb.toString());
    }

    /**
     * 평면 B — 글자가 아닌 것을 <b>호환 전개</b>한 뒤 글자만 남긴다.
     *
     * <p>원문자 {@code ⓕⓤⓒⓚ}(So)와 수학 알파벳 {@code 𝖋𝖚𝖈𝖐}(보조평면 Ll)을 이쪽이 잡는다.
     * 평면 A 는 전자를 통째로 떼어 버려 아무것도 안 남는다.
     *
     * <p>한글은 NFKC 를 거치지 않는다 — 호환 자모 {@code ㅂ}(U+3142)가 조합용으로 바뀌어
     * 사전의 {@code 시ㅂㅏ} 류와 어긋난다. 반대로 반각 자모(U+FFA0–FFDC)는 한글 범위 밖이라
     * NFKC 를 거치고, 그 결과가 조합용 자모라 아래 {@link #compose} 가 음절로 합친다
     * ({@code 시<FFB2><FFC2>} → {@code 시바}).
     */
    private String expandToLetters(String token) {
        StringBuilder sb = new StringBuilder(token.length());
        token.codePoints().forEach(cp -> {
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

    /**
     * 평면 C — 조합용 자모를 사전이 쓰는 <b>호환 자모</b>로 되돌린다.
     *
     * <p>사전에는 자모로 적은 항목이 있고({@code 시ㅂㅏ}·{@code ㅅㅣㅂㅏ} 등 84건) 그것들은 전부
     * <b>호환</b> 자모(U+3131–318E)다. 그런데 반각 자모(U+FFA1–FFDC)를 NFKC 로 접으면 호환이
     * 아니라 <b>조합용</b> 자모(U+1100–11FF)가 나온다 — 같은 글자를 인코딩만 바꿔 적은 것인데
     * 평면이 갈려 한 건도 안 걸렸다. 평면 B 가 그걸 음절로 합쳐 주지만, 합쳐진 결과가 사전에
     * 없는 경우({@code 시바})가 남는다.
     *
     * <p>표는 하드코딩하지 않는다 — 호환 자모 블록을 NFKD 로 돌리면 그 조합용 형태가 나오므로
     * 역으로 뒤집어 만든다. 유니코드가 자모를 더해도 표가 따라간다.
     *
     * <p><b>NFKD 만으로는 초성형밖에 안 나온다.</b> {@code NFKD(ㅂ)} 는 초성 U+1107 하나이고
     * 종성 U+11B8 로는 가는 길이 없다 — 유니코드가 그 둘을 호환 분해로 잇지 않는다. 그래서
     * 종성형으로 적으면 평면 C 가 못 되돌린다(실측: 사전의 자모 항목 73건 중 <b>72건</b>이
     * 종성 표기로 통과했다. 반각 83건과 같은 규모다). 겹자음 9건만 우연히 막혔는데, 그것들은
     * 조합용에 종성 형태만 있어 NFKD 가 바로 종성을 주기 때문이다.
     *
     * <p>그 사각을 글자 이름으로 메운다 — {@code HANGUL JONGSEONG PIEUP} 과
     * {@code HANGUL LETTER PIEUP} 은 접미사가 같다. 이름도 UCD 가 주는 값이라 하드코딩이
     * 아니고, NFKD 표와 <b>충돌이 0건</b>이다(실측: 겹치는 자리 전부 같은 값). 둘을 합치면
     * 94 → 128 이 되고 NFKD 가 먼저다 — 이름으로 안 잡히는 2건이 거기 있다.
     */
    private static String toCompatibilityJamo(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> sb.appendCodePoint(TO_COMPATIBILITY_JAMO.getOrDefault(cp, cp)));
        return sb.toString();
    }

    private static Map<Integer, Integer> buildJamoTable() {
        Map<Integer, Integer> table = new HashMap<>();
        Map<String, Integer> compatibilityByLetterName = new HashMap<>();
        for (int compat = 0x3131; compat <= 0x318E; compat++) {
            String decomposed = Normalizer.normalize(Character.toString(compat), Normalizer.Form.NFKD);
            if (decomposed.codePointCount(0, decomposed.length()) == 1) {
                int conjoining = decomposed.codePointAt(0);
                if (conjoining != compat) {
                    table.putIfAbsent(conjoining, compat);
                }
            }
            String name = Character.getName(compat);
            if (name != null && name.startsWith(COMPATIBILITY_NAME_PREFIX)) {
                compatibilityByLetterName.put(name.substring(COMPATIBILITY_NAME_PREFIX.length()), compat);
            }
        }
        // 이름으로 메우는 둘째 패스. NFKD 가 이미 채운 자리는 건드리지 않는다.
        // 반각(U+FFA1–FFDC)도 같은 표에 넣는다 — NFKC 는 반각을 호환이 아니라 조합용으로
        // 보내므로, 1-패스에서 반각을 바로 접으려면 이 표에 있어야 한다.
        fillByLetterName(table, compatibilityByLetterName, 0x1100, 0x11FF);
        fillByLetterName(table, compatibilityByLetterName, 0xFFA1, 0xFFDC);
        return Map.copyOf(table);
    }

    private static void fillByLetterName(Map<Integer, Integer> table,
            Map<String, Integer> compatibilityByLetterName, int from, int to) {
        for (int variant = from; variant <= to; variant++) {
            String name = Character.getName(variant);
            if (name == null) {
                continue;
            }
            Integer compat =
                    compatibilityByLetterName.get(VARIANT_NAME_PREFIX.matcher(name).replaceFirst(""));
            if (compat != null) {
                table.putIfAbsent(variant, compat);
            }
        }
    }

    /**
     * 떼어 낸 <b>뒤에</b> 합친다. 순서가 결과를 바꾼다.
     *
     * <p>떼기 전에 합치면 음절 안에 끼인 글자가 합성을 막는다 — NFD 로 보낸 {@code 시발} 의
     * 자모 사이에 아무 글자나 하나 끼우면 그 토큰은 영원히 합성되지 않는다(실측: 19종 × 전
     * 위치 76케이스 중 57 미마스킹). 떼고 나서 합치면 그 57 이 닫힌다.
     *
     * <p>여기서 길이가 바뀌어도 된다 — 이 결과는 집합 멤버십 판정에만 쓰이고, 2A·2B 는 토큰
     * <b>전체</b>를 가리므로 span 슬라이싱을 하지 않는다. 길이 보존이 계약인 쪽은 {@link #fold}다.
     */
    private static String compose(String s) {
        return Normalizer.isNormalized(s, Normalizer.Form.NFC)
                ? s
                : Normalizer.normalize(s, Normalizer.Form.NFC);
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
