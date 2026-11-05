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
            // ⚠️ 이 줄이 이 저장소에서 사전 항목을 <가장 자주> 내보내는 자리다. from() 호출은
            // ADMIN 추가·제거마다 2회 · 발행마다 1회 · 그리고 파드마다 스냅샷 교체마다 1회다.
            // 그런데 두 가지가 빠져 있었다:
            //   - warningEntries 가 항목을 visible() 없이 원문으로 박는다 → 보이지 않는 글자가
            //     로그에서도 안 보인다. 그 글자가 바로 이 경고들이 다루는 결함이다.
            //   - 상한이 없다 → 화이트리스트에 경고 걸리는 항목이 늘면 줄 길이가 그만큼 자라고,
            //     그것이 모든 쓰기·모든 발행·모든 파드의 모든 교체에서 나간다.
            // 값을 빼지는 않는다 — 이 경고가 요구하는 조치가 "그 항목을 찾아 고치기" 다.
            Set<String> warnings = dictionary.warnings();
            log.warn("사전에 확인할 항목이 있다({}건): origin={} {}",
                    warnings.size(), origin,
                    ProfanityDictionary.summarize(warnings, recoveryFor(origin)));
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

    /**
     * 꼬리에 도달하는 길 — <b>{@code origin} 마다 다르다.</b>
     *
     * <p>⚠️ 한때 이 자리가 "전량은 ADMIN 조회로 받아 다시 센다" 문자열 하나였고 주석이 "이
     * 입구의 집합은 정본 테이블에서 왔으므로" 였다. {@code from()} 호출처 전수 6곳 /
     * {@code origin} 4종에서 그 전제가 참인 것은 둘뿐이다:
     * <pre>
     *   source     guardSource (변경 전 테이블)        findWords 그대로        참
     *   probe      PublishService 의 문 질의           findWords 그대로        참
     *   source     guardRemoval (테이블 − 제거 항목)   조회가 더 많다          거짓
     *   admin      guardRequested (테이블 + 요청)      그 항목이 조회에 없다   거짓
     *   chat:dict  RefreshHandler (Redis 스냅샷)       조회와 무관             거짓
     * </pre>
     * 마지막 줄은 {@code of()} javadoc 이 스스로 "DB·Redis 직접 수정을 받는 마지막 문" 이라
     * 적는 경로다. 그리고 {@code guardRequested} 쪽은 요청 항목이 {@code plus()} 로 <b>꼬리</b>에
     * 들어가므로 상한 초과 시 잘리는 바로 그 항목이기도 하다 — 당직자가 조회를 쳐도 빈손이다.
     *
     * <p>{@code guardRemoval} 과 {@code guardSource} 가 같은 {@code origin} 을 쓰므로 그 둘은
     * 안내가 같다("조회로 받아 다시 센다"). {@code guardRemoval} 쪽에서는 조회가 <b>더 많이</b>
     * 돌려주는데, 그것은 빈손이 아니라 과다라서 당직자가 꼬리를 찾는 데는 쓸 수 있다.
     */
    private static String recoveryFor(String origin) {
        if (origin.startsWith("chat:filter-word:source")
                || origin.startsWith("chat:filter-word:probe")) {
            return "전량은 ADMIN 조회 /api/v1/chat/filter-words 로 받아 다시 센다";
        }
        if (origin.startsWith("chat:filter-word:admin")) {
            return "요청 항목이 꼬리에 들어가므로 잘린 쪽이 그 항목일 수 있다 — 보낸 값을 확인";
        }
        return "이 집합은 ADMIN 조회로 못 본다(출처=" + origin + ") — 그 출처를 직접 확인";
    }

    /** {@link ProfanityDictionary#visible} 을 그대로 쓴다 — 거부 메시지와 같은 렌더링이어야 한다. */
    @Override
    public String describe(String word) {
        return ProfanityDictionary.visible(word);
    }
}
