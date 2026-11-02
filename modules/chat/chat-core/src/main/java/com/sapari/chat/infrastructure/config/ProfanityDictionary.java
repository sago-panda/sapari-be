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
     * 교체로 두면 설정 한 줄이 사전 850개를 조용히 날린다. 지금 이 두 프로퍼티를 쓰는 곳은 저장소에
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
        words.addAll(splitCsv(extraWords));
        Set<String> whitelist = readResource(whitelistPath);
        whitelist.addAll(splitCsv(extraWhitelist));
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
                while ((line = reader.readLine()) != null) {
                    String word = line.trim();
                    // '#' 주석과 빈 줄은 건너뛴다 — 파일 머리에 출처·선별 근거를 적어 두기 때문이다.
                    if (!word.isEmpty() && !word.startsWith("#")) {
                        out.add(word);
                    }
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

    private static Set<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String word = part.trim();
            if (!word.isEmpty()) {
                out.add(word);
            }
        }
        return out;
    }
}
