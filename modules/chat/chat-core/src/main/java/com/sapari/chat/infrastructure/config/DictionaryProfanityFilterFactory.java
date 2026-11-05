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
     * <p>한때 이 자리가 "전량은 ADMIN 조회로 받아 다시 센다" 문자열 하나였고 주석이 "이 입구의
     * 집합은 정본 테이블에서 왔으므로" 였다. 그 전제는 {@code origin} 에 따라 참/거짓이 갈린다 —
     * 스냅샷 입구의 집합은 Redis 에서 왔고, 운영자가 보낸 항목은 커밋 전이라 조회에 없다.
     *
     * <p>⚠️ <b>호출처를 열거하지 않는다</b>({@link ProfanityFilterFactory} 의 같은 규칙).
     * 열거했더니 바로 다음 라운드에 그 표가 5행인데 실제 호출처는 6곳이고 "참인 것은 둘" 이
     * 실제로는 셋이었다. 세는 방법만 적는다:
     * {@code grep -rn "filterFactory.from(" --include="*.java"} 에서 {@code build/} 를 뺀 것이
     * 호출처이고, 각 호출처가 넘기는 {@code origin} 이 아래 분기를 고른다.
     *
     * <p>⚠️ 한때 그 명령에 {@code .from(} 대안이 붙어 있었다. 그러면 {@code Date.from(…)} ·
     * {@code ChatRole.fromPlatformRole} 류가 같이 잡혀 52행이 되고 "그것이 호출처" 가 거짓이
     * 된다 — <b>틀린 열거를 틀린 계수법으로 바꾼</b> 모양이었다. 세는 방법을 적을 때는 <b>그
     * 명령을 한 번 돌려 본다.</b>
     *
     * <p>분기 키는 <b>리터럴이 아니라 포트 상수</b>다. 복제했을 때는 값이 바뀌면 조용히 일반
     * 분기로 떨어지고, 그때 틀리는 것이 바로 이 안내였다.
     */
    private static String recoveryFor(String origin) {
        if (ProfanityFilterFactory.ORIGIN_SOURCE.equals(origin)
                || ProfanityFilterFactory.ORIGIN_PROBE.equals(origin)) {
            return "전량은 ADMIN 조회 /api/v1/chat/filter-words 로 받아 다시 센다";
        }
        if (ProfanityFilterFactory.ORIGIN_ADMIN.equals(origin)) {
            // ⚠️ 조회 포인터를 <같이> 남긴다. 한때 이 분기가 "보낸 값을 확인" 만 말했는데,
            // 잘리는 꼬리가 보통 요청 항목이 아니다 — warningEntries 가 words 루프를 먼저,
            // whitelist 루프를 나중에 돌므로 kind=PROFANITY 추가에서 요청 항목의 경고는 머리
            // 블록에 들고 꼬리는 화이트리스트 경고로 채워진다. 그 행들은 조회로 전부 받을 수
            // 있다. kind=WHITELIST 추가에서만 요청 항목이 꼬리다 — 두 kind 중 하나에서만 참인
            // 문구였다.
            return "전량은 ADMIN 조회 /api/v1/chat/filter-words 로 받되, 보낸 항목은 아직 커밋 "
                    + "전이라 조회에 없다 — 그 항목이 잘렸는지는 보낸 값으로 확인";
        }
        // ⚠️ 모르는 출처를 로그에 그대로 보간하지 않는다. 지금 호출처 6곳은 전부 포트 상수라
        // 도달 0 이지만, origin 을 포트 공개 상수로 올리면서 "아무 문자열이나 넘길 수 있는
        // 계약" 쪽이 넓어졌다 — 사용자 입력이 섞인 origin 을 넘기는 호출처가 생기면 개행 삽입
        // 로그 위조가 열린다. 제어문자를 접어 그 축을 닫는다(사전 문이 쓰는 것과 같은 접기).
        return "이 집합은 ADMIN 조회로 못 본다(출처=" + ProfanityDictionary.foldControls(origin)
                + ") — 그 출처를 직접 확인";
    }

    /** {@link ProfanityDictionary#visible} 을 그대로 쓴다 — 거부 메시지와 같은 렌더링이어야 한다. */
    @Override
    public String describe(String word) {
        return ProfanityDictionary.visible(word);
    }
}
