package com.sapari.chat.infrastructure.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

import com.sapari.chat.domain.rule.ProfanityFilter;

/**
 * 욕설 사전을 클래스패스 리소스에서 읽는다. 순수 데이터 로더 — 매칭은 {@code ProfanityFilter} 의 몫이다.
 *
 * <p><b>왜 파일인가.</b> 정본 설계는 3계층이다 — Postgres({@code live_schema.chat_filter_word}) →
 * Redis Pub/Sub(변경 전파) → Pod 메모리 trie(런타임 평가). 그런데 streaming-app 에는 DB 의존이
 * <b>0건</b>이고(빌드 파일에 db-core·jpa·jdbc 없음, 데이터소스 설정도 없음) 그 1·2계층을 세우는 것이
 * 이 작업보다 크다. 그래서 이 로더는 <b>3계층만</b> 채운다 — 단어가 코드와 함께 버전 관리되고
 * 리뷰에 diff 로 올라오는 대신, 단어를 바꾸려면 배포가 필요하다는 대가를 받는다.
 *
 * <p>테이블을 이 작업에서 <b>시드하지 않는다.</b> 읽는 코드가 없는 상태로 시드하면 같은 목록이 두 곳에
 * 생기고, 둘이 갈라지는 순간 어느 쪽이 참인지 알 수 없다. 1·2계층이 붙는 작업에서 이 파일을 원본으로
 * 시드를 생성하면 저자가 한 곳으로 유지된다.
 *
 * <p><b>리소스가 없으면 부팅을 실패시킨다.</b> 이 기능의 역사가 그 이유다 — 사전이 비면 마스킹이
 * 조용히 꺼지고({@code displayMessage == originalMessage}) 그것을 알아챌 때쯤 그 기간의 이력이 이미
 * 남는다. 빈 사전을 WARN 으로 넘기던 동안 실제로 그 상태로 떠 있었다. 패키징 실패를 조용한 기능
 * 정지로 바꾸지 않는다.
 */
public final class ProfanityDictionary {

    private static final String WORDS = "/chat/profanity-words.txt";
    private static final String WHITELIST = "/chat/profanity-whitelist.txt";

    // 정본 DDL 의 live_schema.chat_filter_word.word 가 varchar(64) 다. 1계층이 붙을 때
    // 파일이 테이블에 그대로 들어가야 하므로 같은 상한을 여기서 먼저 지킨다.
    private static final int MAX_WORD_LENGTH = 64;

    private final Set<String> words;
    private final Set<String> whitelist;
    private final Set<String> partiallyShadowed;
    private final Set<String> redundantWhitelist;

    private ProfanityDictionary(Set<String> words, Set<String> whitelist,
            Set<String> partiallyShadowed, Set<String> redundantWhitelist) {
        this.words = words;
        this.whitelist = whitelist;
        this.partiallyShadowed = partiallyShadowed;
        this.redundantWhitelist = redundantWhitelist;
    }

    /**
     * 기본 사전을 읽고 추가 단어를 <b>더한다</b>(교체가 아니다).
     *
     * <p>더하는 쪽인 이유: 운영에서 급히 한 단어를 막아야 할 때 배포를 기다리지 않게 하려는 것이고,
     * 교체로 두면 설정 한 줄이 사전 1,106개를 조용히 날린다. 지금 이 두 프로퍼티를 쓰는 곳은 저장소에
     * 0건이라 교체 의미에 기대는 호출자도 없다.
     *
     * @param extraWords     쉼표로 구분한 추가 욕설. null·공백이면 무시한다.
     * @param extraWhitelist 쉼표로 구분한 추가 화이트리스트. null·공백이면 무시한다.
     */
    public static ProfanityDictionary load(String extraWords, String extraWhitelist) {
        return load(WORDS, WHITELIST, extraWords, extraWhitelist);
    }

    /**
     * 리소스 경로를 받는 진입점. (테스트 진입점 — 부재·빈 리소스의 실패를 재려면 경로가 인자여야
     * 한다. 운영 경로는 위 {@link #load(String, String)} 가 상수로 고정한다.)
     */
    static ProfanityDictionary load(String wordsPath, String whitelistPath,
            String extraWords, String extraWhitelist) {
        Set<String> words = readResource(wordsPath);
        Set<String> whitelist = readResource(whitelistPath);
        Set<String> extraW = splitCsv("chat.profanity.words", extraWords);
        Set<String> extraWl = splitCsv("chat.profanity.whitelist", extraWhitelist);
        words.addAll(extraW);
        whitelist.addAll(extraWl);
        // 두 집합이 다 모인 뒤에 검사한다 — 두 방향이 서로를 봐야 하기 때문이다(화이트리스트 항목은
        // 사전을 보고, 사전 항목은 화이트리스트를 본다). 한쪽을 먼저 검사하면 나머지 절반을 못 본다.
        for (String entry : extraWl) {
            rejectUselessWhitelist(entry, words, whitelist);
        }
        // 번들·설정을 가리지 않고 전부 본다. 설정만 보면 번들에 같은 모양이 들어와도 신호가 없다.
        Set<String> shadowed = new LinkedHashSet<>();
        for (String word : words) {
            String by = whitelistInside(asciiLower(word), whitelist, false);
            if (by != null) {
                shadowed.add(word + " ⊂ " + by);
            }
        }
        Set<String> redundant = new LinkedHashSet<>();
        for (String entry : whitelist) {
            String by = whitelistInside(asciiLower(entry), whitelist, true);
            if (by != null) {
                redundant.add(entry + " ⊂ " + by);
            }
        }
        return new ProfanityDictionary(words, whitelist, shadowed, redundant);
    }

    public Set<String> words() {
        return Set.copyOf(words);
    }

    public Set<String> whitelist() {
        return Set.copyOf(whitelist);
    }

    private static Set<String> readResource(String path) {
        try (InputStream in = ProfanityDictionary.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(
                        "욕설 사전 리소스가 없다: " + path + " — 이 모듈의 jar 에 들어가지 않았다. "
                                + "리소스가 빠진 채 뜨면 마스킹이 조용히 꺼지므로(displayMessage == "
                                + "originalMessage) 그 상태로 부팅시키지 않는다.");
            }
            Set<String> out = new LinkedHashSet<>();
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                int lineNo = 0;
                while ((line = reader.readLine()) != null) {
                    lineNo++;
                    String word = strip(line);
                    // '#' 주석과 빈 줄은 건너뛴다 — 파일 머리에 출처·선별 근거를 적어 두기 때문이다.
                    if (word.isEmpty() || word.startsWith("#")) {
                        continue;
                    }
                    reject(path + ":" + lineNo, word);
                    out.add(word);
                }
            }
            if (out.isEmpty()) {
                throw new IllegalStateException(
                        "욕설 사전 리소스가 비어 있다: " + path + " — 위와 같은 이유로 거부한다.");
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("욕설 사전 리소스를 읽지 못했다: " + path, e);
        }
    }

    /**
     * 항목을 받아들이지 않을 이유가 있으면 던진다. 적재 시점에 터뜨리는 쪽을 고른 이유는
     * 둘 다 <b>조용히 사문이 되는</b> 결함이기 때문이다 — 틀린 항목이 목록에 남아 있어서
     * 보호하는 것처럼 보이는데 실제로는 아무것도 하지 않는다.
     *
     * <p>리소스와 설정 CSV <b>두 입구 모두</b>를 지난다. 처음에는 리소스에만 걸었는데, 그러면
     * 급한 차단이 필요한 바로 그 순간에 조용히 실패한다 — 방송 중에
     * {@code CHAT_PROFANITY_WORDS=개 새끼} 를 넣으면 부팅도 되고 적재 로그의 개수도 늘지만
     * 아무것도 막지 못한다. 반대 방향도 같은 입구다: 설정 화이트리스트에 맨 욕설 한 줄이
     * 들어가면 그 욕설이 통째로 풀린다.
     */
    private static void reject(String where, String word) {
        if (containsInvisible(word)) {
            throw new IllegalStateException(
                    "사전 항목에 공백·보이지 않는 글자가 있다: " + where + " \"" + visible(word)
                            + "\" — 공백이 든 항목은 ProfanityFilter 가 공백으로 토큰을 가르므로 "
                            + "어떤 토큰에도 안 맞고, 그 외 보이지 않는 글자가 든 항목은 같은 글자를 "
                            + "품은 입력 하나만 맞는다. 둘 다 평범한 입력은 못 잡으면서 막은 것처럼 보인다.");
        }
        if (word.length() > MAX_WORD_LENGTH) {
            throw new IllegalStateException(
                    "사전 항목이 너무 길다: " + where + " (" + word.length() + "자) — "
                            + "상한 " + MAX_WORD_LENGTH + "자. 단어 파일에 산문이 섞인 것이다.");
        }
    }

    /**
     * 설정으로 들어온 화이트리스트 항목이 <b>의미상</b> 쓸모 있는지 본다.
     *
     * <p>{@link #reject}가 보는 것은 모양(공백·길이)뿐이라 {@code chat.profanity.whitelist=시발}
     * 한 줄이 그대로 통과했고, 그러면 사전 전체에서 그 욕설이 풀렸다(실측: 부팅 성공,
     * {@code 아 시발 짜증나} 가 마스킹되지 않음). 번들 데이터에는 같은 불변식을 테스트가 강제하고
     * 있었는데 설정 입구에만 없었다.
     *
     * <p>부팅을 세우는 쪽을 고른 이유: 이 통로는 방송 중에 쓰라고 둔 것이고, 통과시키면 한 줄
     * 오타가 사전 전체를 조용히 무력화한다. 시끄러운 실패가 조용한 기능 정지보다 낫다는,
     * 이 사전이 비어 있던 동안 이미 치른 교훈과 같은 판단이다.
     */
    private static void rejectUselessWhitelist(String entry, Set<String> words,
            Set<String> whitelist) {
        // 런타임 매칭이 asciiLower 위에서 돌므로 검사도 거기서 해야 한다. 안 그러면 런타임에
        // 똑같이 동작하는 두 값이 입구에서 갈린다 — 'unisex' 는 통과하고 'UNISEX' 는 "어떤 욕설도
        // 품지 않는다" 로 거부되는데 그 메시지가 거짓이다(소문자로 적으면 같은 토큰을 보호한다).
        String lowered = asciiLower(entry);
        Set<String> low = loweredSet(words);
        if (low.contains(lowered)) {
            throw new IllegalStateException(
                    "화이트리스트 항목이 욕설 그 자체다: \"" + entry + "\" — 토큰이 이 항목을 "
                            + "포함하면 통째로 보존되므로, 넣는 순간 그 욕설이 사전 전체에서 풀린다. "
                            + "'시발' 이 아니라 '시발점' 처럼 그 욕설을 품은 더 긴 정상어를 넣는다.");
        }
        if (low.stream().noneMatch(lowered::contains)) {
            throw new IllegalStateException(
                    "화이트리스트 항목이 어떤 욕설도 품지 않는다: \"" + entry + "\" — 보호할 대상이 "
                            + "없어 아무 일도 하지 않으면서, 이 조각을 붙인 토큰을 통째로 통과시키는 "
                            + "우회로만 만든다.");
        }
        if (!hasHangulSyllable(removeAll(lowered, low))) {
            throw new IllegalStateException(
                    "화이트리스트 항목에서 욕설을 빼면 한글이 남지 않는다: \"" + entry + "\" — 지키려는 "
                            + "'말' 이 없다는 뜻이다. '8시발' 의 잔여는 '8' 뿐이라 정상어를 지키는 게 "
                            + "아니라 숫자를 붙인 우회로를 만든다. '시발점' 처럼 한글이 남아야 한다.");
        }
    }

    /**
     * 설정 단어 중 화이트리스트 항목을 품은 것들. {@code "단어 ⊂ 화이트리스트항목"} 꼴이다.
     *
     * <p>이들은 <b>리터럴 토큰으로만</b> 안 걸린다. 처음에는 "영원히 매치되지 않는다" 로 보고
     * 부팅을 세웠는데 그 전제가 거짓이었다 — 실측에서 다섯 모양 중 넷의 동작이 바뀐다.
     * {@code 전염병같은놈} 을 더하면 {@code 전염병 같은놈}·{@code 전염병1같은놈}·
     * {@code 전염병.같은놈}·{@code 전염병 같 은놈} 이 전부 마스킹되고, 안 걸리는 것은 공백·기호
     * 없는 {@code 전염병같은놈} 하나뿐이다. 2A·2B 우회 경로는 화이트리스트를 보지 않기 때문이다.
     *
     * <p>그래서 거부하지 않는다. 거부하면 방송 중에 쓰라고 둔 통로가 정당한 긴급차단을 막고
     * (실측: {@code 전염병새끼}·{@code 시발역놈}·{@code 성교육충} 등 5/5 거부) 그 실패가
     * 빈 생성 실패 = 전 파드 채팅 정지다. 대신 적재하고 호출자가 경고로 남긴다.
     */
    public Set<String> partiallyShadowed() {
        return Set.copyOf(partiallyShadowed);
    }

    /**
     * 다른 화이트리스트 항목에 이미 덮이는 항목. {@code "항목 ⊂ 덮는항목"} 꼴이다.
     *
     * <p>거부하지 않는 이유는 <b>해롭지 않기 때문</b>이다 — 긴 항목을 품은 토큰은 짧은 항목도
     * 반드시 품으므로 결과가 바뀌지 않는다. 반대로 한 줄 오타로 전 파드 채팅이 멈추는 대가는
     * 크다. 같은 근거로 가려진 단어 쪽도 WARN 으로 내렸는데 이쪽만 거부로 두면, 효과가 4/5 인
     * 값은 통과시키면서 효과가 0 인 값에 부팅을 세우는 거꾸로 된 정책이 된다.
     *
     * <p>거부가 남아 있는 것은 넷 중 셋이다 — 맨 욕설 · 보호 대상 없음 · 잔여 비한글. 셋 다
     * <b>우회로를 새로 연다.</b> 경계는 "쓸모없음" 이 아니라 "해로움" 이다.
     */
    public Set<String> redundantWhitelist() {
        return Set.copyOf(redundantWhitelist);
    }

    /**
     * 이 문자열 안에 들어 있는 화이트리스트 항목. 없으면 null.
     *
     * <p>{@code skipExactMatch} 가 두 방향을 가른다. 화이트리스트 항목끼리 비교할 때는 자기
     * 자신과의 비교를 빼야 모든 항목이 자기에게 덮였다고 나온다. 사전 단어와 비교할 때는 빼면
     * 안 된다 — 두 집합이 다르므로 값이 같은 것은 자기비교가 아니라 <b>완전히 가려진 단어</b>다.
     * 한 함수를 양쪽에 그냥 재사용했다가 {@code CHAT_PROFANITY_WORDS=시발점} 이 거부도 경고도
     * 없이 조용히 적재되는 구멍을 만들었다.
     */
    private static String whitelistInside(String lowered, Set<String> whitelist,
            boolean skipExactMatch) {
        for (String other : loweredSet(whitelist)) {
            if (skipExactMatch && other.equals(lowered)) {
                continue;
            }
            if (lowered.contains(other)) {
                return other;
            }
        }
        return null;
    }

    private static Set<String> loweredSet(Set<String> source) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : source) {
            out.add(asciiLower(s));
        }
        return out;
    }

    private static String removeAll(String text, Set<String> parts) {
        String out = text;
        for (String part : parts) {
            if (!part.isEmpty() && out.contains(part)) {
                out = out.replace(part, "");
            }
        }
        return out;
    }

    private static boolean hasHangulSyllable(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= '\uAC00' && s.charAt(i) <= '\uD7A3') {
                return true;
            }
        }
        return false;
    }

    // 매처와 같은 평면으로 접는다(대소문자 + 전각). 여기서 따로 구현하면 두 평면이 갈리고,
    // 그러면 입구가 통과시킨 것을 매처가 다르게 보거나 그 반대가 된다 — 이미 두 번 겪었다.
    private static String asciiLower(String s) {
        return ProfanityFilter.fold(s);
    }

    /**
     * 눈에 안 보이는 글자가 섞였는지 본다.
     *
     * <p>{@code isWhitespace} 하나로는 모자란다. NBSP(U+00A0)는 그게 false 이고, ZWSP(U+200B)·
     * WJ(U+2060)·BOM(U+FEFF)은 서식 문자이며, 한글 채움문자 넷(U+115F·U+1160·U+3164·U+FFA0)은
     * 아예 '문자'(Lo)로 분류된다. 그런데 {@code ProfanityFilter} 의 토큰 정규식은 이들 중 어느 것도 구분자로 보지
     * 않는다 — 그래서 이런 글자가 섞인 항목은 <b>평범한 입력을 영원히 못 잡는다.</b> 적재는 되고
     * 개수도 오르는데 아무것도 막지 못한다. 복붙이 만드는 전형 경로이고, 설정 CSV 에는 번들
     * 리소스 같은 사전 검사가 없다.
     */
    private static boolean containsInvisible(String word) {
        return word.codePoints().anyMatch(ProfanityDictionary::isInvisible);
    }

    /**
     * char 가 아니라 <b>코드포인트</b>로 본다. 보조평면의 태그 문자(U+E0000~E007F)는 UTF-16 에서
     * 서로게이트 쌍이라, {@code charAt} 로 돌면 {@code getType} 이 FORMAT 이 아니라 SURROGATE 를
     * 돌려줘 검사를 그대로 통과한다.
     *
     * <p>가능한 곳은 <b>분류</b>로 막는다 — 열거는 빠뜨린 하나가 그대로 통로가 되고 실제로 두 번
     * 그랬다(NBSP, 그리고 한글 채움문자 넷 중 둘). 다만 전수는 아니다. 유니코드가 '기호'(So)로
     * 분류하면서 공백처럼 렌더하는 글자가 있어 그쪽은 열거로 받는다 — 아래 {@code isBlankLooking}.
     * So 를 통째로 막지는 않는다. 그러면 이모지 항목을 나중에 못 넣는다.
     * 현재 사전·화이트리스트가 쓰는 분류는 Nd·Ll·Lo·Po·Sk·Sm 뿐이라(실측) 아래 거부군과 겹치지 않는다.
     */
    private static boolean isInvisible(int cp) {
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)
                || isHangulFiller(cp) || isBlankLooking(cp)) {
            return true;
        }
        return switch (Character.getType(cp)) {
            // 서식·제어·미할당·사용자영역: 보이지 않거나 글자가 아니다.
            case Character.FORMAT, Character.CONTROL, Character.UNASSIGNED, Character.PRIVATE_USE,
                    Character.SURROGATE -> true;
            // 비간격 표시: 변이 선택자(U+FE0F)와 결합 악센트가 여기다. 폭이 0 이라 눈에 안 보인다.
            case Character.NON_SPACING_MARK, Character.ENCLOSING_MARK -> true;
            default -> false;
        };
    }

    // 공백처럼 렌더되지만 유니코드가 기호(So)·문자로 분류해 위 어느 가지에도 안 걸리는 것들.
    // U+2800 은 인터넷에서 'invisible character' 로 가장 널리 복사되는 글자다.
    private static boolean isBlankLooking(int cp) {
        return cp == 0x2800 || cp == 0xFFFC;
    }

    // 판정은 ProfanityFilter 에 둔다 — 매처도 같은 넷을 떼어 내야 하고, 두 평면이 갈리면
    // 한쪽이 막는 것을 다른 쪽이 통과시킨다(로더만 닫고 매처를 열어 둔 적이 있다).
    private static boolean isHangulFiller(int cp) {
        return ProfanityFilter.isHangulFiller(cp);
    }

    /** 보이지 않는 글자는 코드포인트로 보여 줘야 당직자가 지목할 수 있다. */
    private static String visible(String word) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c == ' ' || !isInvisible(c)) {
                out.append(c);
            } else {
                out.append(String.format("\\u%04X", (int) c));
            }
            // 서로게이트는 짝마다 \\uXXXX 두 개로 찍힌다 — 지목에는 그것으로 충분하다.
        }
        return out.toString();
    }

    // BOM(U+FEFF)을 뗀다. strip() 은 Character.isWhitespace 기준이라 BOM 을 남기는데, 남으면
    // 파일 1행이 주석 판정('#' 로 시작)을 비껴가 그 줄이 통째로 단어가 된다.
    // NBSP 등 다른 보이지 않는 글자는 여기서 안 떼지지만 아래 containsInvisible 이 거부한다.
    private static String strip(String line) {
        String out = line.strip();
        return out.isEmpty() || out.charAt(0) != '\uFEFF' ? out : out.substring(1).strip();
    }

    private static Set<String> splitCsv(String property, String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        String[] parts = csv.split(",");
        for (int i = 0; i < parts.length; i++) {
            String word = strip(parts[i]);
            if (word.isEmpty()) {
                continue;
            }
            reject(property + " 의 " + (i + 1) + "번째 항목", word);
            out.add(word);
        }
        return out;
    }
}
