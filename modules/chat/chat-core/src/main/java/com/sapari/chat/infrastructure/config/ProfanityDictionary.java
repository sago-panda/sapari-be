package com.sapari.chat.infrastructure.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

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

    private ProfanityDictionary(Set<String> words, Set<String> whitelist) {
        this.words = words;
        this.whitelist = whitelist;
    }

    /**
     * 기본 사전을 읽고 추가 단어를 <b>더한다</b>(교체가 아니다).
     *
     * <p>더하는 쪽인 이유: 운영에서 급히 한 단어를 막아야 할 때 배포를 기다리지 않게 하려는 것이고,
     * 교체로 두면 설정 한 줄이 사전 1,112개를 조용히 날린다. 지금 이 두 프로퍼티를 쓰는 곳은 저장소에
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
        words.addAll(splitCsv("chat.profanity.words", extraWords));
        // 아래 화이트리스트 검사가 words 를 본다 — 그래서 words 쪽 두 줄이 먼저 와야 한다.
        Set<String> whitelist = readResource(whitelistPath);
        for (String entry : splitCsv("chat.profanity.whitelist", extraWhitelist)) {
            rejectUselessWhitelist(entry, words);
            whitelist.add(entry);
        }
        return new ProfanityDictionary(words, whitelist);
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
        if (containsWhitespace(word)) {
            throw new IllegalStateException(
                    "사전 항목에 공백이 있다: " + where + " \"" + word + "\" — "
                            + "ProfanityFilter 는 공백으로 토큰을 가르므로 공백이 든 항목은 어떤 "
                            + "토큰에도 매치되지 않는다. 보호하는 것처럼 보이지만 사문이다.");
        }
        if (word.length() > MAX_WORD_LENGTH) {
            throw new IllegalStateException(
                    "사전 항목이 너무 길다: " + where + " (" + word.length() + "자) — "
                            + "상한 " + MAX_WORD_LENGTH + "자. 단어 파일에 산문이 섞인 것이다.");
        }
    }

    // isWhitespace 만으로는 NBSP(U+00A0)를 놓친다 — isWhitespace 는 false 인데 ProfanityFilter 의
    // 토큰 정규식 \S+ 는 NBSP 를 구분자로 보지 않아, NBSP 섞인 항목은 평범한 공백이 든 입력을
    // 영원히 못 잡는다("개<NBSP>새끼" 를 넣으면 "개 새끼" 가 안 걸린다). 복붙이 NBSP 를 만드는
    // 전형 경로이고 설정 CSV 에는 "현행 리소스" 같은 재고가 없으므로 isSpaceChar 까지 본다.
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
    private static void rejectUselessWhitelist(String entry, Set<String> words) {
        if (words.contains(entry)) {
            throw new IllegalStateException(
                    "화이트리스트 항목이 욕설 그 자체다: \"" + entry + "\" — 토큰이 이 항목을 "
                            + "포함하면 통째로 보존되므로, 넣는 순간 그 욕설이 사전 전체에서 풀린다. "
                            + "'시발' 이 아니라 '시발점' 처럼 그 욕설을 품은 더 긴 정상어를 넣는다.");
        }
        if (words.stream().noneMatch(entry::contains)) {
            throw new IllegalStateException(
                    "화이트리스트 항목이 어떤 욕설도 품지 않는다: \"" + entry + "\" — 보호할 대상이 "
                            + "없어 아무 일도 하지 않으면서, 이 조각을 붙인 토큰을 통째로 통과시키는 "
                            + "우회로만 만든다.");
        }
    }

    private static boolean containsWhitespace(String word) {
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                return true;
            }
        }
        return false;
    }

    // BOM(U+FEFF)을 뗀다. strip() 은 Character.isWhitespace 기준이라 BOM 을 남기는데, 남으면
    // 파일 1행이 주석 판정('#' 로 시작)을 비껴가 그 줄이 통째로 단어가 된다.
    // NBSP(U+00A0)는 여기서 안 떼지지만 아래 containsWhitespace 가 거부한다.
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
