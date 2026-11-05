package com.sapari.chat.infrastructure.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

import com.sapari.chat.domain.rule.ProfanityFilter;

/**
 * 사전 항목을 들여보내는 <b>문</b>. 리소스·설정 CSV·전파된 스냅샷 셋이 전부 여기를 지난다.
 *
 * <p><b>파일은 더 이상 정본이 아니다</b>(SPR-165). 정본은
 * {@code live_schema.chat_filter_word} 이고, 이 파일은 그 테이블이 비어 있을 때 한 번 들어가는
 * <b>시드의 원본</b>이다. 한때 여기 "그래서 이 로더는 3계층만 채운다 — 단어를 바꾸려면 배포가
 * 필요하다는 대가를 받는다" 와 "테이블을 이 작업에서 시드하지 않는다" 가 적혀 있었다. 둘 다 그
 * 작업에서는 참이었고 지금은 거짓이다: 1·2계층이 붙었고({@code ChatDictionaryBootstrap} ·
 * {@code ProfanityDictionaryRefreshHandler}) 시드도 그때 적어 둔 대로 <b>이 파일을 원본으로</b>
 * 만들어진다.
 *
 * <p>그래도 파일이 남아 있는 이유는 둘이다 — 시드의 원본이고, 스냅샷이 아직 없거나 읽히지 않을 때
 * Pod 이 그것으로 뜬다. 즉 마스킹이 꺼진 채 뜨는 경로를 이 파일이 막는다.
 *
 * <p><b>리소스가 없으면 부팅을 실패시킨다.</b> 이 기능의 역사가 그 이유다 — 사전이 비면 마스킹이
 * 조용히 꺼지고({@code displayMessage == originalMessage}) 그것을 알아챌 때쯤 그 기간의 이력이 이미
 * 남는다. 빈 사전을 WARN 으로 넘기던 동안 실제로 그 상태로 떠 있었다. 패키징 실패를 조용한 기능
 * 정지로 바꾸지 않는다.
 */
public final class ProfanityDictionary {

    private static final String WORDS = "/chat/profanity-words.txt";
    private static final String WHITELIST = "/chat/profanity-whitelist.txt";

    /**
     * 사전 항목 하나의 상한 — <b>길이 제약이 아니라 지연 예산이다.</b>
     *
     * <p>{@code ProfanityFilter} 의 2B 는 인접 2~K 토큰을 조인하고 K = 사전 <b>최장 항목</b>의
     * 접은 길이다. 즉 이 수는 항목 하나의 모양이 아니라 <b>모든 사용자의 모든 메시지</b>가 치르는
     * 비용의 천장이고, 그 천장을 정하는 것은 사전에서 가장 긴 한 줄이다.
     *
     * <p><b>2026-11-05 실측</b>(199자/100토큰 메시지 · 1,078항목 사전 · 1초 예산 × 3라운드 중간값.
     * 추가 항목의 길이가 그대로 K 가 된다 — 번들 최장이 7 이므로):
     * <pre>
     *   K    µs/건   msg/s   배수
     *   7    144.7    6,910   1.0x   ← 오늘 번들
     *  12    296.7    3,371   2.1x
     *  16    468.6    2,134   3.2x   ← 이 상수
     *  32  1,272.0      786   8.8x
     *  64  2,844.4      352  19.7x   ← 전에 허용했던 최대
     * </pre>
     *
     * <p><b>64 에서 16 으로 내렸다</b>(SPR-165). 64 가 괜찮았던 이유는 그 값에 닿는 길이 배포뿐이었기
     * 때문이다 — 코드 리뷰를 지난 설정 한 줄. 사전 전파가 붙으면서 ADMIN 요청 하나로 그 길이 열렸고,
     * 오타로 문장을 붙여 넣으면 함대 처리량이 19.7x 떨어진다. 16 은 측정된 점이고 도달 가능한
     * 열화의 83% 를 걷어낸다. 번들 최장이 7 이라 여유가 9 자 있다.
     *
     * <p>⚠️ <b>이 수를 올리는 것은 스키마 변경이 아니라 채팅 지연 변경이다.</b> 정본
     * {@code live_schema.chat_filter_word.word} 는 {@code varchar(64)} 라 열 쪽은 64 까지 받지만,
     * 런타임 예산이 그보다 엄하다 — 열이 넓은 것이 허락이 아니다. 올릴 때는 위 표에서 값을 읽고
     * 그 배수를 받아들일 수 있는지 먼저 판단할 것. {@code ProfanityFilter} 의 {@code maxWindow}
     * 필드와 2B 루프에 같은 표시가 있다.
     */
    static final int MAX_WORD_LENGTH = 16;

    private final Set<String> words;
    private final Set<String> whitelist;
    private final Set<String> warnings;

    private ProfanityDictionary(Set<String> words, Set<String> whitelist,
            Set<String> warnings) {
        this.words = words;
        this.whitelist = whitelist;
        this.warnings = warnings;
    }

    /**
     * 기본 사전을 읽고 추가 단어를 <b>더한다</b>(교체가 아니다).
     *
     * <p>더하는 쪽인 이유: 운영에서 급히 한 단어를 막아야 할 때 배포를 기다리지 않게 하려는 것이고,
     * 교체로 두면 설정 한 줄이 사전 1,078개를 조용히 날린다. 지금 이 두 프로퍼티를 쓰는 곳은 저장소에
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
        // 번들 항목은 번들 사전에 대해서만 판정한다 — 아래 루프가 쓴다. addAll 전에 떠 둬야 한다.
        Set<String> bundledWords = Set.copyOf(words);
        Set<String> bundledWhitelist = Set.copyOf(whitelist);
        Set<String> sanitized = new LinkedHashSet<>();
        Set<String> extraW = splitCsv("chat.profanity.words", extraWords, sanitized);
        Set<String> extraWl = splitCsv("chat.profanity.whitelist", extraWhitelist, sanitized);
        words.addAll(extraW);
        whitelist.addAll(extraWl);
        // 두 집합이 다 모인 뒤에 검사한다 — 두 방향이 서로를 봐야 하기 때문이다(화이트리스트 항목은
        // 사전을 보고, 사전 항목은 화이트리스트를 본다). 한쪽을 먼저 검사하면 나머지 절반을 못 본다.
        //
        // 번들 파일 항목도 본다. 전에는 extraWl 만 돌렸고, 그래서 번들 .txt 에 '시발' 이나
        // '8시발' 을 적으면 거부도 경고도 없이 적재됐다 — 그 파일을 지키는 것이 로더가 아니라
        // 테스트 하나뿐이었다(실측: 번들 입구는 LOADED, 같은 값을 설정 CSV 로 주면 거부). 사전
        // 파일만 고치는 커밋은 `build -x test` 로 통과하므로 그 한 겹은 끊기기 쉬운 겹이다.
        // 지금 번들 38건은 (1)·(3)을 전부 지나므로 이 줄이 오늘 바꾸는 동작은 없다 —
        // 바꾸는 것은 <앞으로 잘못 들어올 항목>이 어디서 걸리는지다.
        //
        // ⚠️ 번들 항목은 <번들 사전>에 대해서만 판정한다. 전수 집합에 대고 재면 설정으로 들어온
        // 단어 하나가 번들 화이트리스트 항목과 같아지는 순간 규칙 (1)이 터지는데, 그 상황은
        // 이미 <경고>로 결정돼 있다 — "부팅을 세우지 않는다" 를 고르고 거부를 걷은 자리다
        // (warningEntries 의 첫 루프 · ChatBeansConfigWarningLogTest). 전수로 재면 그 결정이
        // 조용히 뒤집히고, 방송 중 CHAT_PROFANITY_WORDS=시발점 한 줄이 전 파드를 세운다
        // (되돌려 재면 테스트 1건이 바로 그것을 잡는다).
        for (String entry : bundledWhitelist) {
            rejectUselessWhitelist(entry, bundledWords);
        }
        for (String entry : extraWl) {
            rejectUselessWhitelist(entry, words);
        }
        // 번들·설정을 가리지 않고 전부 본다. 설정만 보면 번들에 같은 모양이 들어와도 신호가 없다.
        Set<String> warnings = new LinkedHashSet<>(sanitized);
        warnings.addAll(warningEntries(words, whitelist));
        return new ProfanityDictionary(words, whitelist, warnings);
    }

    /**
     * 이미 메모리에 있는 두 집합을 <b>리소스와 같은 문으로</b> 들여보낸다 — 정본에서 전파돼 온 사전이
     * 이 입구를 쓴다.
     *
     * <p><b>왜 같은 문이어야 하는가.</b> 전파가 붙으면 사전이 세 입구로 들어온다 — 번들 리소스 ·
     * 설정 CSV · 정본 스냅샷. 세 번째에만 검사를 안 걸면 거부의 근거들이 그 입구에서 전부 무효가
     * 된다: 보이지 않는 글자가 든 항목은 평범한 입력을 영원히 못 잡고(막은 것처럼 보인다),
     * 예산을 넘는 항목 하나는 함대 처리량을 떨어뜨리고, 욕설 그 자체인 화이트리스트 항목 하나는
     * 그 욕설을 사전 전체에서 푼다. 그 셋은 어디서 들어왔는지와 무관하게 같은 결함이다.
     *
     * <p><b>던지는 것이 곧 거부다.</b> 호출자가 이것을 잡아 <b>교체를 포기하고 이전 사전을 계속
     * 쓴다</b> — 리소스 입구가 부팅을 세우는 것과 방향이 다른데, 도는 Pod 를 사전 한 줄의 오타로
     * 죽이는 것이 그 오타보다 나쁘기 때문이다. 쓰기 경로(관리자 엔드포인트)가 같은 검사를 먼저
     * 돌려 애초에 이 상태에 도달하지 않게 하고, 여기는 그 경로를 지나지 않은 쓰기(DB·Redis 직접
     * 수정)를 받는 <b>마지막 문</b>이다.
     *
     * <p>빈 욕설 집합도 거부한다. 리소스가 비어 있을 때와 같은 이유다 — 비면 마스킹이 조용히
     * 꺼지고({@code displayMessage == originalMessage}) 그걸 알아챌 때쯤 그 기간의 이력이 이미
     * 남는다. 스냅샷이 아직 없는 상태와 "스냅샷이 비었다" 는 호출자가 가르고, 전자는 번들로 뜬다.
     *
     * @param origin 실패 메시지에 실을 출처 이름(예: {@code "chat:dict"}). 세 입구 중 어디서
     *               떨어졌는지가 당직자에게 가야 한다
     */
    public static ProfanityDictionary of(String origin, Set<String> words, Set<String> whitelist) {
        Set<String> admitted = new LinkedHashSet<>();
        for (String word : words) {
            String stripped = strip(word);
            if (stripped.isEmpty()) {
                continue;
            }
            reject(origin + ":words", stripped);
            admitted.add(stripped);
        }
        Set<String> admittedWhitelist = new LinkedHashSet<>();
        for (String entry : whitelist) {
            String stripped = strip(entry);
            if (stripped.isEmpty()) {
                continue;
            }
            reject(origin + ":whitelist", stripped);
            admittedWhitelist.add(stripped);
        }
        if (admitted.isEmpty()) {
            throw new IllegalStateException(
                    "전파된 사전의 욕설 집합이 비어 있다: " + origin + " — 적용하면 마스킹이 조용히 "
                            + "꺼지므로 거부한다. 스냅샷이 아직 없는 것과 다르다.");
        }
        // 화이트리스트는 <전수> 단어 집합에 대고 본다. 번들 경로가 번들끼리만 보는 것은 설정으로
        // 들어온 단어 하나가 부팅을 세우지 않게 하려는 완화이고, 여기는 평면이 하나다 — 두 집합이
        // 같은 테이블에서 같이 왔으므로 '나중에 섞인 쪽' 이 없다.
        for (String entry : admittedWhitelist) {
            rejectUselessWhitelist(entry, admitted);
        }
        return new ProfanityDictionary(admitted, admittedWhitelist,
                warningEntries(admitted, admittedWhitelist));
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
        // ⚠️ 여기서 재는 길이가 K 와 같은 것은 <호출 순서> 덕이다. 이 메서드는 항상 strip() 뒤에
        // 불리고 strip 의 마지막 줄이 normalizeEntry(NFC) 다 — 즉 받는 값이 이미 NFC 평면이고,
        // ProfanityFilter 가 K 를 세는 평면도 같다(fold 는 NFC 위에서 1:1 이라 길이를 보존한다).
        //
        // 이 순서를 뒤집으면 예산이 뚫린다. NFC 는 <늘릴 수> 있다 — 유니코드 전수에서 코드포인트가
        // 늘어나는 글자가 85건이고(실측 2026-11-05), 최악은 악기 기호다: U+1D160 하나가 NFC 에서
        // 3 코드포인트 · UTF-16 6자가 된다. 그래서 '가'×14 + U+1D160 은 원문 16자인데 NFC 20자다.
        // 길이 검사를 normalizeEntry <앞으로> 옮기면 그 줄이 통과하고 K 가 예산을 넘는다.
        // 지금 그것을 막는 것은 이 호출 순서 하나이고, 그래서 그 벡터를 테스트가 못 박는다
        // (ProfanityDictionaryTest 의 theBudgetIsMeasuredOnTheNfcPlane).
        if (word.length() > MAX_WORD_LENGTH) {
            throw new IllegalStateException(
                    "사전 항목이 예산보다 길다: " + where + " (" + word.length() + "자) — "
                            + "상한 " + MAX_WORD_LENGTH + "자. 이 상한은 글자 수 제약이 아니라 "
                            + "지연 예산이다: 가장 긴 항목 하나가 2B 윈도우 폭을 정하고 그 비용을 "
                            + "모든 메시지가 치른다(MAX_WORD_LENGTH javadoc 의 실측 표). "
                            + "단어 파일에 산문이 섞였거나, 구절이 예산을 넘은 것이다.");
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
    private static void rejectUselessWhitelist(String entry, Set<String> words) {
        // 런타임 매칭이 asciiLower 위에서 돌므로 검사도 거기서 해야 한다. 안 그러면 런타임에
        // 똑같이 동작하는 두 값이 입구에서 갈린다 — 'unisex' 는 통과하고 'UNISEX' 는 "어떤 욕설도
        // 품지 않는다" 로 거부되는데 그 메시지가 거짓이다(소문자로 적으면 같은 토큰을 보호한다).
        String lowered = asciiLower(entry);
        Set<String> low = loweredSet(words);
        if (low.contains(lowered)) {
            throw new IllegalStateException(
                    "화이트리스트 항목이 욕설 그 자체다: \"" + entry + "\" — 두 span 이 정확히 "
                            + "겹쳐 덮이므로, 넣는 순간 그 욕설이 사전 전체에서 풀린다. "
                            + "'시발' 이 아니라 '시발점' 처럼 그 욕설을 품은 더 긴 정상어를 넣는다.");
        }
        if (low.stream().noneMatch(lowered::contains)) {
            // 품는 욕설이 0건이면 푸는 것도 0건이다 — 규칙 (3)의 문면("싼 접두를 준다")이 그
            // 값에 대해 거짓이 된다. 그 경우는 규칙 (2) 쪽(아무것도 보호하지 않음)이 맞고
            // 경고로 끝난다. 이 분기가 없으면 'q'·'8'·'茶'·'!!' 가 거짓 메시지로 부팅을 세운다.
            //
            // ⚠️ 그 전제가 참인 것은 이 검사가 <접은 평면>에서 돌기 때문이다. 화이트 span 은
            // 접은 토큰에서 항목 리터럴이 일치한 구간이고, 욕설 span 이 거기 완전히 들어가려면
            // 그 욕설 문자열이 항목 텍스트 안에 있어야 한다 — 품는 욕설이 0건이면 그런 부분
            // 문자열이 없으므로 uncovered 가 떨어뜨릴 span 이 없다(실측: 단독·접두·접미 ×
            // 사전 전수에서 증분 누출 0건, 양성 대조군 whitelist=시발 은 1건으로 검출).
            // 판정을 원문 표기로 옮기면 깨진다 — 'ＦＵＣＫ' 가 "욕설 0건" 으로 분류돼 이
            // 분기로 빠지지만 런타임은 fold 후 'fuck' 을 덮는다. 지금은 위 asciiLower 가 그
            // 자리를 막고 있고, 그것을 테스트가 고정한다.
            return;
        }
        String residue = removeAll(lowered, low);
        if (!hasHangulSyllable(residue) && !hasTwoLetters(residue)) {
            throw new IllegalStateException(
                    "화이트리스트 항목에서 욕설을 빼면 글자가 남지 않는다: \"" + entry + "\" — 공격자에게 "
                            + "싼 접두를 준다. '8시발' 을 넣으면 '8' 만 붙여 '8시발'·'8시발이다' 가 "
                            + "통과한다. 지킬 '말' 이 있어야 한다 — '시발점' 처럼.");
        }
    }

    /**
     * 적재는 됐지만 <b>아무 일도 하지 않는</b> 항목들. 사람이 읽는 문장으로 낸다.
     *
     * <p>두 종류다. 사전 단어가 화이트리스트 항목과 <b>같으면</b> 두 span 이 언제나 정확히
     * 겹쳐 영원히 버려진다. 화이트리스트 항목이 <b>어떤 욕설도 품지 않으면</b> 덮을 span 이
     * 없어 아무것도 보존하지 못한다.
     *
     * <p>둘 다 거부하지 않는다 — 해롭지 않기 때문이다. span 단위로 바꾸기 전에는 후자가
     * 토큰 전체를 통과시키는 우회로였고 그래서 거부했는데, 이제는 덮을 span 이 없으면 그냥
     * 아무 일도 안 한다(실측: {@code 감사합니다시발} 이 화이트리스트 유무와 무관하게
     * {@code 감사합니다***}). 거부는 우회로를 <b>새로 여는</b> 것에만 건다.
     *
     * <p>창구를 하나로 둔 이유: 전에 둘로 나눴다가 한쪽에만 테스트를 붙였고, 나머지 분기는
     * 지워도 아무것도 안 깨졌다. 같은 실수를 두 라운드 연속 했다.
     */
    public Set<String> warnings() {
        return Set.copyOf(warnings);
    }

    private static Set<String> warningEntries(Set<String> words, Set<String> whitelist) {
        Set<String> lowerWhitelist = loweredSet(whitelist);
        Set<String> lowerWords = loweredSet(words);
        Set<String> out = new LinkedHashSet<>();
        for (String word : words) {
            if (lowerWhitelist.contains(asciiLower(word))) {
                out.add("사전 단어 \"" + word + "\" — 같은 화이트리스트 항목이 있어 어느 자리에서도 "
                        + "걸리지 않는다");
            }
        }
        for (String entry : whitelist) {
            String lowered = asciiLower(entry);
            if (lowerWords.stream().noneMatch(lowered::contains)) {
                out.add("화이트리스트 항목 \"" + entry + "\" — 어떤 욕설도 품지 않아 아무것도 "
                        + "보호하지 않는다");
                continue;
            }
            // 욕설을 뺀 잔여에 한글 음절이 없는 항목. 거부하지 않는다 — 정당한 라틴 항목이
            // 전부 부팅을 세웠고(되돌려 재면 6/6 거부) 방송 중에 라틴 오탐을 막을 길이 없다. 다만
            // 한글 잔여보다 짧고 싸서 '8시발' 류와 거리가 가까우므로 당직자에게 보인다.
            if (!hasHangulSyllable(removeAll(lowered, lowerWords))) {
                out.add("화이트리스트 항목 \"" + entry + "\" — 욕설을 빼면 한글 음절이 남지 않는다"
                        + "(잔여: \"" + removeAll(lowered, lowerWords) + "\"). 라틴 정상어라면 의도된"
                        + " 것이고, 아니면 그 욕설을 싸게 푸는 접두일 수 있으니 확인할 것");
            }
        }
        return out;
    }



    private static Set<String> loweredSet(Set<String> source) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : source) {
            out.add(asciiLower(s));
        }
        return out;
    }

    /**
     * {@code text} 에서 사전 항목을 모두 지운 잔여. <b>긴 항목부터</b> 지운다.
     *
     * <p>순서가 결과를 바꾼다 — {@code 8sibal} 에는 사전 항목 {@code siba}·{@code sibal} 이
     * 둘 다 들어 있어서, 짧은 쪽을 먼저 지우면 잔여가 {@code 8l} 이고 긴 쪽을 먼저 지우면
     * {@code 8} 이다.
     *
     * <p><b>이 순서가 떠받치는 것은 경고문의 정확성이다 — 규칙 (3)의 판정이 아니다.</b>
     * 당직자가 경고문의 잔여를 읽고 '지킬 말이 무엇인가' 를 판단하므로 그 값이 쪼개진 글자
     * 조각이면 안 된다. 판정 쪽은 순서와 무관하다 — {@code 8l} 의 글자는 {@code l} 하나뿐이고
     * ({@code 8} 은 숫자다) 문턱이 글자 둘이라 어느 순서로도 거부된다. 한때 이 자리에 "순서를
     * 되돌리면 {@code 8sibal} 이 수락된다" 고 적었는데, 그건 문턱이 '글자 하나' 였던 중간
     * 상태의 값이고 지금은 거짓이다. 되돌림으로 깨지는 것은 경고문 잔여 테스트 하나다.
     */
    private static String removeAll(String text, Set<String> parts) {
        List<String> longestFirst = new ArrayList<>(parts);
        longestFirst.sort(Comparator.comparingInt(String::length).reversed());
        String out = text;
        for (String part : longestFirst) {
            if (!part.isEmpty() && out.contains(part)) {
                out = out.replace(part, "");
            }
        }
        return out;
    }

    /**
     * 잔여에 <b>글자가 둘 이상</b> 있는가. 한글 음절만 보던 것을 넓혔다.
     *
     * <p>좁혔을 때의 대가가 가설이 아니었다 — {@code passivation}·{@code unisex}·
     * {@code sexual}·{@code Essex}·{@code pussycat} 같은 <b>정당한 라틴 항목이 전부 부팅을
     * 세웠다</b>(실측: 완화 전 — 이 문턱과 위의 '욕설 0건 건너뛰기' 를 <b>둘 다</b> 되돌린
     * 상태 — 에서 6/6 이 거부된다. 이 문턱만 되돌리면 1/6 이다({@code pussycat} 하나) —
     * 사전에서 {@code sex}·{@code siva} 를 뺀 뒤로 나머지 다섯은 품는 욕설이 0건이라 건너뛰기
     * 쪽이 먼저 받는다. 오늘 데이터로 이 문턱이 실제로 지키는 것은 {@code pussycat} 과
     * 아래 1글자 잔여 축이다).
     * 그런데 라틴 항목 중 영단어에 걸리는 것이 지금도 5건이고({@code .claude/scripts/
     * check-ascii-entries.sh}) 그중 하나가 방송 중에 오탐을 내면 설정으로 못 막는다 —
     * 사전 항목을 빼는 배포밖에 길이 없고, {@code sex}·{@code siva} 에서 이미 두 번 그 길로
     * 갔다. 거부의 경계는 '해로움' 이므로 측정된 공격 모양({@code 8시발} — 잔여가 숫자 한 글자)
     * 만 거부하고, 한글이 아닌 글자 잔여는 아래 {@code warnings()} 로 내린다.
     *
     * <p>한글 음절은 하나로도 낱말이라 그쪽은 그대로 한 글자다({@code 시발점} → {@code 점}).
     * 라틴에만 글자 <b>둘</b>을 요구하는 이유: 한 글자는 한글이든 라틴이든 싸다. 잔여가 {@code x}
     * 하나면({@code xpussy} → {@code x}) {@code 8시발} 과 같은 모양이고, 라틴 정상어의 잔여는 둘 이상이다
     * (실측: {@code pussycat} → {@code cat}. {@code Essex}·{@code unisex} 는 사전에서
     * {@code sex} 를 뺀 뒤로 품는 욕설이 0건이어서 규칙 (3)에 아예 들어오지 않고 규칙 (2)
     * 경고로 간다 — 데이터가 바뀌면 그 데이터를 예시로 쓴 문장이 같이 썩는다).
     */
    private static boolean hasTwoLetters(String s) {
        return s.codePoints().filter(Character::isLetter).count() >= 2;
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
     *
     * <p>⚠️ 바로 위 "이모지 항목을 나중에 못 넣는다" 는 <b>So 축에 대해서만</b> 참이다. So 자체는
     * 안 막지만 이모지 피커가 내보내는 <b>표준형</b>은 변이 선택자(U+FE0F)나 ZWJ 를 달고 오고,
     * 그 둘은 FORMAT·NON_SPACING_MARK 가지에 걸린다 — 즉 ❤️(U+2764 U+FE0F) 를 그대로 붙여
     * 넣으면 So 를 안 막았어도 거부됐다. 설정 CSV 입구에서는 그 폭 없는 글자를 떼고 경고로
     * 바꿨다({@code dropZeroWidth}). 리소스 입구는 그대로 거부다.
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

    // 판정은 ProfanityFilter 에 둔다 — 매처도 같은 넷을 떼어 내야 하고, 로더와 매처가 갈리면
    // 한쪽이 막는 것을 다른 쪽이 통과시킨다(로더만 닫고 매처를 열어 둔 적이 있다).
    private static boolean isHangulFiller(int cp) {
        return ProfanityFilter.isHangulFiller(cp);
    }

    /**
     * 보이지 않는 글자는 코드포인트로 보여 줘야 당직자가 지목할 수 있다.
     *
     * <p>패키지 공개다 — {@code DictionaryProfanityFilterFactory} 가 이것을
     * {@code ProfanityFilterFactory.describe} 로 내보낸다. 발행 쪽이 같은 일을 하려고 전체를
     * 코드 포인트로 펼치는 것을 한 번 만들었고(보이는 글자까지 로그로 나갔다) 그것을 이 하나로
     * 모았다.
     */
    static String visible(String word) {
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

    /**
     * 항목을 매칭 평면으로 접는다 — 지금은 NFC.
     *
     * <p>메시지는 {@code filter()} 입구에서 NFC 로 접히는데 항목이 그대로면 둘이 영원히 만나지
     * 못한다. 실측: {@code CHAT_PROFANITY_WORDS} 에 NFD 로 쓴 단어는 거부도 경고도 없이
     * 적재되고 NFC·NFD 메시지를 <b>둘 다</b> 못 막았다. 반대로 NFD 로 쓴 정당한 화이트리스트
     * 항목은 "어떤 욕설도 품지 않는다" 라는 <b>거짓 메시지와 함께</b> 부팅을 세웠다.
     *
     * <p>NFD 는 macOS 복사가 그대로 주는 형식이고, 이 두 키는 방송 중에 쓰라고 둔 통로다.
     *
     * <p>⚠️ 여기서 접는 것은 <b>NFC 까지</b>다. 매처의 2A·2B 전처리는 비한글 코드포인트에
     * NFKC 까지 쓰므로 그 둘은 같은 평면이 아니다 — "비교에 참여하는 평면은 전부 같아야
     * 한다" 는 1-패스 축에만 참이다. 실해는 오늘 0 이다(사전 1,078·화이트리스트 38 중 평면
     * 차이로 사문화되는 항목 0건). 다만 설정으로 호환 문자가 섞인 항목이 들어오면 2A·2B
     * 에서만 조용히 사문이 된다. 항목 쪽도 NFKC 로 접으려면 사전의 호환 자모 83건이 조합용
     * 으로 바뀌므로 그쪽을 먼저 옮겨야 한다.
     */
    private static String normalizeEntry(String word) {
        return Normalizer.isNormalized(word, Normalizer.Form.NFC)
                ? word
                : Normalizer.normalize(word, Normalizer.Form.NFC);
    }

    /**
     * 이 문이 항목을 보는 평면 — <b>저장하는 쪽이 같은 평면을 써야 한다.</b>
     *
     * <p>이것을 열어 둔 이유가 결함 하나다. 관리자 쓰기 경로가 문에는 <i>접은</i> 값을 보여
     * 주고 테이블에는 <i>원문</i>을 저장하고 있었다. 그러면 한 요청에서 두 결과가 나온다 —
     * {@code "시발\n"} 은 문이 {@code 시발} 로 보고 통과시키는데 테이블에는 개행이 든 행이
     * 남아, 그 뒤 모든 스냅샷이 코덱 자기검증에서 깨지고 <b>전 함대의 전파가 멈춘다</b>.
     * 반대 방향도 있다: {@code "개새끼 "} 를 저장해 두면 {@code ?word=개새끼} 삭제가 0행이고
     * 응답은 "이미 그 상태였다"({@code changed=false}) 인데 실제로는 여전히 집행 중이다.
     *
     * <p>그래서 쓰기 전에 이 메서드로 접는다. <b>저장되는 값이 집행되는 값과 같아야</b>
     * {@code remove} 와 {@code uk (kind, word)} 가 성립한다.
     */
    public static String normalize(String word) {
        return strip(word);
    }

    // BOM(U+FEFF)을 뗀다. strip() 은 Character.isWhitespace 기준이라 BOM 을 남기는데, 남으면
    // 파일 1행이 주석 판정('#' 로 시작)을 비껴가 그 줄이 통째로 단어가 된다.
    // NBSP 등 다른 보이지 않는 글자는 여기서 안 떼지지만 아래 containsInvisible 이 거부한다.
    //
    // ⚠️ 선행 BOM 을 <수렴할 때까지> 뗀다. 한때 한 번만 뗐고, 그래서 이 함수가 고정점이 아니었다
    // (실측: 선행 BOM 2개 이상 7벡터 중 5종이 normalize(normalize(x)) != normalize(x)). 그 비고정점이
    // 관리자 쓰기에서 <저장값 ≠ 집행값>을 만들었다 — fold() 가 한 번 접어 저장하고, 사전 문 of() 가
    // 받은 값에 strip 을 한 번 더 걸어 판정하므로, 저장된 "<BOM>시발" 이 문에는 "시발" 로 보인다.
    // 피해는 그 행을 API 로 지울 수 없다는 것이다 — 정확히 같은 2중 BOM 을 다시 보내야만 맞고,
    // 어떤 평범한 입력도 그 행을 가리키지 못한다(접기가 모든 후보를 "시발" 로 접는다).
    //
    // 공백을 사이에 끼운 모양도 같이 수렴한다(<BOM> <BOM>시발) — 떼고 나서 다시 strip 하기 때문이다.
    private static String strip(String line) {
        String out = line.strip();
        while (!out.isEmpty() && out.charAt(0) == '\uFEFF') {
            out = out.substring(1).strip();
        }
        return normalizeEntry(out);
    }

    private static Set<String> splitCsv(String property, String csv, Set<String> warnings) {
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
            String where = property + " 의 " + (i + 1) + "번째 항목";
            word = dropZeroWidth(where, word, warnings);
            if (word.isEmpty()) {
                continue;
            }
            reject(where, word);
            out.add(word);
        }
        return out;
    }

    /**
     * 설정 CSV 항목에서 <b>폭 없는</b> 보이지 않는 글자를 떼고 경고를 남긴다. 공백은 떼지 않는다.
     *
     * <p>이 저장소가 세운 경계는 "거부의 기준은 쓸모없음이 아니라 해로움" 이다. 폭 없는 글자가
     * 붙은 항목은 해롭지 않다 — 우회로를 열지 않고 그냥 아무것도 안 막는다. 그런데 {@code reject}
     * 가 그것으로 던지고 있었고, 이 통로는 <b>방송 중에 쓰라고 둔 긴급 경로</b>다. 실측으로
     * {@code words=❤️}(U+2764 U+FE0F)와 끝에 U+FE0F 가 붙은 {@code fuck️} 이 전부 거부였다 —
     * 즉 이모지 피커가 내보내는 표준형(변이 선택자 부착·ZWJ 결합)을 그대로 붙여 넣으면 부팅이
     * 실패하고, 죽는 것은 욕설 필터가 아니라 채팅 전체(WS 세션·입장·영속)이며 전 파드가 같이 죽는다.
     *
     * <p>공백을 남겨 두는 이유는 다르다. 공백이 든 항목은 토큰 경계를 넘으므로 어떤 토큰에도
     * 안 맞는데, 그것을 떼어 붙이면(개 새끼 → 개새끼) 운영자의 의도를 <b>추측</b>하는 것이 된다.
     * 그래서 공백은 그대로 {@code reject} 로 보낸다 — 메시지가 무엇을 고쳐야 하는지 알려 준다.
     *
     * <p>번들 리소스 쪽은 바꾸지 않는다. 그쪽 실패는 배포 시점이라 방송을 끊지 않는다.
     */
    private static String dropZeroWidth(String where, String word, Set<String> warnings) {
        StringBuilder kept = new StringBuilder();
        word.codePoints().forEach(cp -> {
            if (isInvisible(cp) && !Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) {
                return;
            }
            kept.appendCodePoint(cp);
        });
        String out = kept.toString();
        if (out.equals(word)) {
            return word;
        }
        warnings.add("설정 항목 " + where + " \"" + visible(word) + "\" 에서 폭 없는 글자를 뗐다"
                + (out.isEmpty() ? " — 남는 글자가 없어 버렸다" : " — \"" + out + "\" 로 적재한다")
                + ". 이모지 변이 선택자(U+FE0F)·ZWJ·ZWSP 류가 붙으면 그 글자를 품은 입력 하나만"
                + " 맞으므로, 떼는 쪽이 운영자가 의도한 차단에 가깝다.");
        return out;
    }
}
