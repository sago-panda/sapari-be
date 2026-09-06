package com.sapari.streamingapp.config;

import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

import com.sapari.chat.domain.rule.ChatPermissionPolicy;
import com.sapari.chat.domain.rule.ProfanityFilter;
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
     * 욕설 필터. v1은 설정({@code chat.profanity.words}/{@code .whitelist}, 기본 빈값)에서 단어를 받는다.
     * 빈 set이면 pass-through(마스킹 없음). 실제 출처는 chat_filter_word 테이블 로더(#24) — streaming-app이
     * DB-free라 로더 설계가 별도 트랙이므로, 그 전까지 config 기반으로 부팅·운영 주입을 가능케 한다.
     */
    @Bean
    public ProfanityFilter profanityFilter(
            @Value("${chat.profanity.words:}") String words,
            @Value("${chat.profanity.whitelist:}") String whitelist) {
        Set<String> profanity = toSet(words);
        if (profanity.isEmpty()) {
            // 빈 사전은 조용한 pass-through다 — 필터가 붙어 있는데 아무것도 가리지 않는다. 그 상태로 뜨면
            // 마스킹본과 원문이 같은 값이 되고, 그 둘을 가르는 것으로 서 있는 것들(두 필드 저장, 방주인
            // 전용 원문 토글, 팬아웃 4분기)이 전부 차이 없는 값을 나른다. 기능이 꺼진 것을 배포 뒤에
            // 알아채면 이미 그 기간의 이력이 남으므로, 부팅 시점에 시끄럽게 알린다.
            log.warn("욕설 사전이 비어 있다 — 마스킹이 전혀 걸리지 않는다(displayMessage == originalMessage). "
                    + "chat.profanity.words 를 주입하거나, 사전 로더가 붙기 전까지 이 상태가 의도인지 확인할 것");
        }
        return new ProfanityFilter(profanity, toSet(whitelist));
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
