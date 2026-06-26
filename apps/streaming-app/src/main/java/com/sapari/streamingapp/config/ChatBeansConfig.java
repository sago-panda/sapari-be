package com.sapari.streamingapp.config;

import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.domain.rule.ProfanityFilter;
import com.sapari.global.time.TimeProvider;

/**
 * SendChatService가 의존하는 순수 정책·시간 빈을 streaming-app 컨텍스트에 제공한다.
 * (ChatPermissionPolicy·ProfanityFilter는 @Component가 아닌 순수 클래스, TimeProvider는 com.sapari.global 패키지라
 * streaming-app 스캔 밖 → 여기서 명시 @Bean.)
 */
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
     * 욕설 필터. v1은 설정({@code chat.profanity.words}/{@code .whitelist}, 기본 빈값)에서 단어를 받는다.
     * 빈 set이면 pass-through(마스킹 없음). 실제 출처는 chat_filter_word 테이블 로더(#24) — streaming-app이
     * DB-free라 로더 설계가 별도 트랙이므로, 그 전까지 config 기반으로 부팅·운영 주입을 가능케 한다.
     */
    @Bean
    public ProfanityFilter profanityFilter(
            @Value("${chat.profanity.words:}") String words,
            @Value("${chat.profanity.whitelist:}") String whitelist) {
        return new ProfanityFilter(toSet(words), toSet(whitelist));
    }

    private Set<String> toSet(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }
}
