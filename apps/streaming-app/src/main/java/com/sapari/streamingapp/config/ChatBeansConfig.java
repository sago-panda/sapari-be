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
        // 적재는 됐지만 아무 일도 하지 않는 항목을 알려 준다. 거부하지 않는 이유는 해롭지
        // 않기 때문이고(결과가 안 바뀐다), 방송 중에 쓰라고 둔 통로가 한 줄 오타로 전 파드
        // 채팅을 세우는 대가가 더 크다. 다만 "막았다" 로 믿고 넘어가는 것이 이 경고가 막으려는
        // 것이라, 조용히 넘기지도 않는다.
        if (!dictionary.ineffective().isEmpty()) {
            log.warn("사전에 효과 없는 항목이 있다: {}", dictionary.ineffective());
        }
        return new ProfanityFilter(dictionary.words(), dictionary.whitelist());
    }
}
