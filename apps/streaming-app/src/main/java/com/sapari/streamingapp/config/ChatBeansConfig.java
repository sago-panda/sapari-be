package com.sapari.streamingapp.config;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.domain.rule.ProfanityFilter;
import com.sapari.chat.infrastructure.config.ProfanityDictionary;
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
     */
    @Bean
    public ProfanityFilter profanityFilter(
            @Value("${chat.profanity.words:}") String extraWords,
            @Value("${chat.profanity.whitelist:}") String extraWhitelist) {
        ProfanityDictionary dictionary = ProfanityDictionary.load(extraWords, extraWhitelist);
        log.info("욕설 사전 적재 — 욕설 {}개 · 화이트리스트 {}개",
                dictionary.words().size(), dictionary.whitelist().size());
        // 설정으로 더한 단어가 화이트리스트 항목을 품으면 리터럴 토큰에서만 안 걸린다(우회형은
        // 여전히 잡는다). 적재는 하되 알려 준다 — 급히 막은 사람이 "막았다" 와 "리터럴은 못 막는다"
        // 를 구분할 수 있어야 하기 때문이다. 부팅을 세우면 정당한 긴급차단까지 막힌다.
        if (!dictionary.partiallyShadowed().isEmpty()) {
            log.warn("사전 단어가 화이트리스트에 일부 가려진다 — 리터럴 토큰으로는 안 걸리고 "
                    + "공백·기호가 섞인 우회형만 걸린다: {}", dictionary.partiallyShadowed());
        }
        // 해롭지는 않다(결과가 안 바뀐다). 다만 화이트리스트가 길어질수록 우회면이 넓어지므로
        // 효과 없는 항목이 쌓이는 것을 알려 준다.
        if (!dictionary.redundantWhitelist().isEmpty()) {
            log.warn("화이트리스트 항목이 더 짧은 항목에 이미 덮인다 — 효과가 없으면서 우회면만 "
                    + "넓힌다: {}", dictionary.redundantWhitelist());
        }
        return new ProfanityFilter(dictionary.words(), dictionary.whitelist());
    }
}
