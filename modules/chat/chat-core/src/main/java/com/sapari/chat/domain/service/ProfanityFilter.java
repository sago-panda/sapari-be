package com.sapari.chat.domain.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
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
 * 대소문자 무관. 자모 분해 분산(예: 시 ㅂ ㅏ)은 음절 합성이 필요해 본 버전 범위 밖.
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
                String lw = asciiLower(w);
                lowerWords.add(lw);
                max = Math.max(max, lw.length());
            }
        }
        Set<String> lowerWhite = new HashSet<>();
        for (String w : whitelist) {
            if (w != null && !w.isBlank()) {
                lowerWhite.add(asciiLower(w));
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
                    && profanity.findAll(asciiLower(stripped[i])).size()
                        > profanity.findAll(asciiLower(t)).size()) {
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
                if (profanitySet.contains(asciiLower(joined.toString()))) {
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
        List<int[]> spans = profanity.findAll(asciiLower(token));
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
        return whitelistMatcher.containsAny(asciiLower(s));
    }

    // ASCII 전용 소문자화(A–Z만, 길이 보존). 사전이 한국어(무대소문자)+ASCII 영어뿐이라 충분하고,
    // String.toLowerCase()의 로케일 의존·길이 변화(예: 'İ' → "i̇" 2글자)로 span이 어긋나
    // maskToken 슬라이싱이 StringIndexOutOfBounds로 터지는 것을 막는다(어떤 입력에도 total).
    private static String asciiLower(String s) {
        char[] cs = s.toCharArray();
        for (int i = 0; i < cs.length; i++) {
            if (cs[i] >= 'A' && cs[i] <= 'Z') {
                cs[i] = (char) (cs[i] + 32);
            }
        }
        return new String(cs);
    }

    // 토큰 내부 특수문자·숫자 제거(글자만 남김) — 2A·2B 전처리.
    private String stripNonLetters(String token) {
        StringBuilder sb = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isLetter(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
