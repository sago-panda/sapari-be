package com.sapari.chat.infrastructure.config;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.sapari.chat.application.port.ProfanityFilterFactory;
import com.sapari.chat.domain.rule.ProfanityFilter;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link ProfanityFilterFactory} 구현 — {@link ProfanityDictionary} 의 문을 그대로 쓴다.
 *
 * <p>얇은 것이 의도다. 검사를 여기서 다시 쓰면 번들 입구와 전파 입구가 <b>다른 문</b>을 지나게
 * 되고, 그 둘이 갈리는 순간 한쪽이 통과시킨 것을 다른 쪽이 거부한다 — 이 사전에서 이미 두 번
 * 겪은 모양이다(설정 CSV 에만 검사가 없던 때, 번들 파일에만 검사가 없던 때).
 *
 * <p><b>경고를 여기서 적는다.</b> 적재는 됐지만 아무 일도 하지 않는 항목은 거부하지 않는다 —
 * 결과를 바꾸지 않기 때문이다. 다만 "막았다" 로 믿고 넘어가는 것이 그 경고가 막으려는 것이라
 * 조용히 넘기지도 않는다. 호출자에게 돌려주지 않고 여기서 적는 이유는 그것이 <b>문에서 본
 * 사실</b>이고 호출자의 판단이 들어갈 자리가 없기 때문이다.
 *
 * <p>{@code @Component} 다 — streaming-app 이 {@code com.sapari.chat} 을 스캔하므로 거기서
 * 올라온다. <b>live-app 도 이 문을 쓴다</b>: 사전 쓰기의 선검사와 접는 평면이 같은 문이어야
 * 하므로 그 앱이 {@code @Bean} 으로 따로 세운다({@code ChatModerationBeansConfig}).
 *
 * <p>⚠️ 한때 여기 "live-app 은 전파를 받지 않으므로 필요하지도 않다" 고 적어 뒀다. 관리 API 가
 * 붙으면서 거짓이 됐다 — 쓰기 경로가 이 문을 지나고, 저장되는 값도 이 문의 평면으로 접힌다.
 * 두 앱이 각자 인스턴스를 갖는 것은 {@code ChatMessageMapperImpl} 과 같은 모양이다(상태가 없는
 * 변환이라 공유할 이유가 없다).
 */
@Slf4j
@Component
public class DictionaryProfanityFilterFactory implements ProfanityFilterFactory {

    @Override
    public ProfanityFilter from(String origin, Set<String> words, Set<String> whitelist) {
        ProfanityDictionary dictionary = ProfanityDictionary.of(origin, words, whitelist);
        if (!dictionary.warnings().isEmpty()) {
            log.warn("사전에 확인할 항목이 있다: origin={} {}", origin, dictionary.warnings());
        }
        return new ProfanityFilter(dictionary.words(), dictionary.whitelist());
    }

    /**
     * {@link ProfanityDictionary#normalize} 를 그대로 쓴다 — 여기서 다시 쓰면 검사하는 평면과
     * 저장하는 평면이 갈리고, 그게 이 메서드가 생긴 이유인 결함이다.
     */
    @Override
    public String normalize(String word) {
        return ProfanityDictionary.normalize(word);
    }
}
