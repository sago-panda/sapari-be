package com.sapari.streamingapp.config;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.domain.rule.ProfanityFilter;
import com.sapari.chat.infrastructure.config.ProfanityDictionary;
import com.sapari.chat.infrastructure.config.SwappableProfanityFilter;
import com.sapari.global.time.TimeProvider;

/**
 * SendChatService가 의존하는 순수 정책·시간 빈을 streaming-app 컨텍스트에 제공한다.
 * (ChatPermissionPolicy·ProfanityFilter는 @Component가 아닌 순수 클래스, TimeProvider는 com.sapari.global 패키지라
 * streaming-app 스캔 밖 → 여기서 명시 @Bean.)
 */
@Slf4j
@Configuration
public class ChatBeansConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public TimeProvider timeProvider(Clock clock) {
        return new TimeProvider(clock);
    }

    @Bean
    public ChatPermissionPolicy chatPermissionPolicy() {
        return new ChatPermissionPolicy();
    }

    /**
     * 욕설 필터. 사전은 chat-core 의 클래스패스 리소스에서 오고, 설정
     * ({@code chat.profanity.words}/{@code .whitelist})은 거기에 <b>더한다</b>.
     *
     * <p>한때 설정이 유일한 출처였고 기본값이 빈 문자열이었다. 그래서 아무것도 주입하지 않으면
     * 마스킹이 조용히 꺼진 채로 떴다 — {@code displayMessage == originalMessage} 가 되고, 그 둘을
     * 가르는 것으로 서 있는 것들(두 필드 저장 · 방주인 전용 원문 토글 · 팬아웃 분기)이 전부 차이
     * 없는 값을 나른다. 실제로 그 상태였고 부팅 WARN 만 남아 있었다.
     *
     * <p>지금은 리소스가 없거나 비면 {@link ProfanityDictionary} 가 <b>부팅을 실패시킨다</b> —
     * 그래서 "사전이 비어 있다" WARN 은 이 자리에서 도달 불가가 됐고 지웠다. 조용한 기능 정지보다
     * 시끄러운 부팅 실패가 낫다는 판단은 이 앱의 액추에이터 노출 가드와 같은 것이다.
     *
     * <p>⚠️ <b>빈 타입이 {@code ProfanityFilter} 에서 {@code SwappableProfanityFilter} 로 바뀌었다.</b>
     *
     * <p>{@code SendChatService} 가 {@code ProfanityFilterSource} 를 받으므로 이 앱이 그 구현을
     * 준다. 전에는 {@code ProfanityFilter} 자체를 빈으로 줬는데, 그러면 <b>사전을 런타임에 바꿀 수
     * 없다</b> — 서비스의 필드가 {@code final} 이고 부팅 때 한 번 조립되니 빈을 갈아 끼워도 안 닿는다.
     * 사전 변경의 유일한 길이 배포였던 이유가 그것이다.
     *
     * <p>구체 타입으로 돌려주는 것이 의도다. 이 클래스는 포트 <b>둘</b>을 구현하는데
     * ({@code ProfanityFilterSource} 읽기 · {@code ProfanityFilterInstaller} 쓰기) 한쪽 포트
     * 타입으로 선언하면 다른 쪽이 빈 타입에서 사라져, 그것을 쓰는 쪽이 캐스팅하거나 별 빈을
     * 또 만들게 된다. 구체 타입으로 주면 두 포트 주입이 같은 객체로 해결된다 — 읽는 쪽은
     * {@code SendChatService}, 쓰는 쪽은 {@code ProfanityDictionaryRefreshHandler} 다.
     */
    @Bean
    public SwappableProfanityFilter profanityFilterSource(
            @Value("${chat.profanity.words:}") String extraWords,
            @Value("${chat.profanity.whitelist:}") String extraWhitelist) {
        ProfanityDictionary dictionary = ProfanityDictionary.load(extraWords, extraWhitelist);
        log.info("욕설 사전 적재 — 욕설 {}개 · 화이트리스트 {}개",
                dictionary.words().size(), dictionary.whitelist().size());
        // ⚠️ 설정 CSV 항목의 <수명>을 적는다. 이 둘은 부팅 때 한 번 합쳐질 뿐이고, 정본
        // 스냅샷이 도착하면 필터가 통째로 교체되면서 함께 빠진다
        // (ProfanityDictionaryRefreshHandler 가 스냅샷만으로 새 필터를 만든다). 그 교체는
        // live-app 이 한 번 뜨거나 ADMIN 이 한 번 쓰면 일어난다.
        //
        // 합치지 않는 것이 의도다 — 합치면 <집행되는 집합>이 live-app 의 쓰기 선검사가 보는
        // <검증되는 집합>과 달라진다. 그러면 설정 단어 하나가 정본 화이트리스트 항목과 겹치는
        // 순간 streaming-app 의 교체만 거부되고 쓰기는 통과해, 게이트 평면과 런타임 평면이
        // 갈리는 그 결함이 되살아난다(이 티켓이 반복해 닫은 계급이다).
        //
        // 그래서 조용히 사라지지 않게 수명을 여기서 말한다. 운영 중 변경은 ADMIN API
        // (/api/v1/chat/filter-words)로 — 그쪽은 감사되고 함대에 전파된다.
        if (hasText(extraWords) || hasText(extraWhitelist)) {
            // 원문을 싣지 않는다 — 그 원문이 곧 욕설이고, 운영자는 자기 설정을 이미 들고 있다.
            // 필요한 것은 "설정 항목이 있다 · 몇 개다 · 사라진다" 이고 개수로 충분하다.
            log.warn("설정 CSV 사전 항목이 적재됐다 — 이 항목들은 <정본 스냅샷이 도착하면 교체와 "
                    + "함께 빠진다>(live-app 부팅 또는 ADMIN 쓰기 한 번). 지속되는 변경은 "
                    + "ADMIN API(/api/v1/chat/filter-words)로 할 것. "
                    + "chat.profanity.words={}개 chat.profanity.whitelist={}개",
                    countCsv(extraWords), countCsv(extraWhitelist));
        }
        // 적재는 됐지만 아무 일도 하지 않는 항목을 알려 준다. 거부하지 않는 이유는 해롭지
        // 않기 때문이고(결과가 안 바뀐다), 방송 중에 쓰라고 둔 통로가 한 줄 오타로 전 파드
        // 채팅을 세우는 대가가 더 크다. 다만 "막았다" 로 믿고 넘어가는 것이 이 경고가 막으려는
        // 것이라, 조용히 넘기지도 않는다.
        if (!dictionary.warnings().isEmpty()) {
            // ⚠️ 위 WARN 은 개수만 싣는데 이 줄은 <항목이 든 문장>을 싣는다. 같은 정책을 두 줄이
            // 다르게 쓰는 것처럼 보이므로 근거를 적는다 — 두 줄의 성질이 다르다.
            //
            // 위쪽은 설정 항목이 <있으면 언제나> 나가는 정상 경로다. 거기서 값을 싣는 것은 로그에
            // 욕설을 상시로 쌓는 것이고, 운영자는 자기 설정을 이미 들고 있으니 얻는 것이 없다.
            // 이 줄은 <적재는 됐지만 아무 일도 하지 않는> 항목만 나가는 비정상 경로이고, 운영자가
            // 해야 하는 일이 "그 항목을 CSV 에서 찾아 고치기" 라 어느 항목인지가 필요하다.
            //
            // ⚠️ 한때 이 자리에 "그 문장의 렌더링은 사전 문이 만든다(ProfanityDictionary.visible)"
            // 고 적혀 있었다. 거짓이었다 — warningEntries 는 항목을 원문으로 박고, visible() 이
            // 걸리는 것은 dropZeroWidth 가 만드는 문장뿐이다. 그리고 이 줄은 상한도 없어서,
            // 경고가 늘면 줄 길이가 그만큼 자랐다. 두 결함이 바로 아래 형제 줄
            // (DictionaryProfanityFilterFactory)에서 닫힌 그 둘과 같다.
            //
            // 그래서 같은 창구를 쓴다 — summarize() 가 상한에서 끊고, 그 상한은 포트의
            // LOG_ENTRY_LIMIT 하나다. 접기(visible)는 거기서 뺐다: 이 경로가 만들 수 있는 경고에
            // 보이지 않는 글자가 들어올 수 없고(dropZeroWidth 는 자기가 이미 접는다) 접으면
            // 이모지가 서로게이트 두 쪽으로 쪼개져 지목이 더 어려워진다.
            log.warn("사전 설정에 확인할 항목이 있다({}건): {}",
                    dictionary.warnings().size(),
                    ProfanityDictionary.summarize(dictionary.warnings()));
        }
        return new SwappableProfanityFilter(
                new ProfanityFilter(dictionary.words(), dictionary.whitelist()));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 로그에 실을 항목 개수 — <b>로더가 번호를 매기는 방식과 같게</b> 센다.
     *
     * <p>⚠️ 한때 {@code split(",", -1)} 이었고 근거가 "빈 칸도 센다. 운영자가 세는 위치와
     * 맞아야 \"3번째\" 가 통한다" 였다. <b>그 근거가 거꾸로였다.</b> "3번째" 를 찍는 쪽인
     * {@code ProfanityDictionary.splitCsv} 는 {@code csv.split(",")} 로 세는데 그것은 <b>후행
     * 빈 칸을 버린다.</b> 실측: {@code "시발,지랄,"} → 이쪽은 3, 로더 인덱스는 최대 2. 후행
     * 콤마 하나로 이 로그가 존재하지 않는 "3번째" 를 가리켰다.
     */
    private static int countCsv(String value) {
        return hasText(value) ? value.split(",").length : 0;
    }
}
