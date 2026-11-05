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
        // 다듬기를 <of() 앞>에서 한다. of() 뒤면 of() 가 생 origin 을 받아 reject 와 빈집합
        // 거부가 그것을 예외 메시지에 박고, 그 메시지는 ManageFilterWordsService 의
        // log.error("… 사유={}") 와 RefreshHandler 로 나간다 — 로그 인자만 다듬으면 닫은
        // 집합이 안 닫은 집합의 진부분집합이 된다. 소비자(예외 메시지 · 로그 인자 · 꼬리
        // 안내)가 같은 값을 받게 한다.
        //
        // 접기만으로는 부족하다 — 접기는 쪼개짐만 막고 길이는 안 막는다. sanitizeForLog 가
        // 둘을 묶는다. 실측(origin 5,000자 · 보이지 않는 글자 거부 벡터): 예외 메시지
        // 5,174자 → 422자.
        //
        // String.valueOf 는 origin == null 의 NPE 를 막는다. 감싸지 않으면 foldControls 가
        // length() 를 먼저 불러 터지고, 그 NPE 는 ManageFilterWordsService 의
        // catch (IllegalStateException) 를 통과해 ADMIN 쓰기를 500 으로 만든다. 이 포트는
        // 생 문자열을 받으므로 호출처 성질에 기대지 않는다.
        String safeOrigin = ProfanityDictionary.sanitizeForLog(String.valueOf(origin));
        ProfanityDictionary dictionary = ProfanityDictionary.of(safeOrigin, words, whitelist);
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
            // 2번 인자는 다듬은 값이다 — 꼬리 안내 안에서만 다듬으면 summarize 가 꼬리를
            // 붙이는 칸(경고 수가 상한 초과)에서만 접히고 그 밖에서는 생 origin 이 나간다.
            log.warn("사전에 확인할 항목이 있다({}건): origin={} {}",
                    warnings.size(), safeOrigin,
                    ProfanityDictionary.summarize(warnings, recoveryFor(origin, safeOrigin)));
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
     * 열거했더니 바로 다음 라운드에 그 표의 행 수와 실제 호출처 수가 어긋났고 "참인 것은 둘"
     * 도 틀렸다. 세는 방법만 적는다:
     * {@code grep -rn "filterFactory.from(" --include="*.java"} 에서 {@code build/} <b>와 이
     * 파일</b>을 뺀 것이 호출처이고, 각 호출처가 넘기는 {@code origin} 이 아래 분기를 고른다.
     * 이 파일을 빼는 이유는 <b>이 문장 자신이 그 패턴에 걸리기 때문</b>이다 — 안 빼면 지시를
     * 그대로 따른 사람이 호출처를 하나 더 센다. 이 계수법은 여러 번 수렴했고, 틀린 회차는
     * 전부 <b>그 명령을 돌려 보지 않아서</b> 틀렸다.
     *
     * <p>분기 키는 <b>리터럴이 아니라 포트 상수</b>다. 복제하면 값이 바뀔 때 조용히 일반
     * 분기로 떨어지고, 그때 틀리는 것이 이 안내다.
     *
     * <p>분기는 생 {@code origin} 으로, 보간은 다듬은 값으로 한다 — 분기 키와 로그 값은
     * 다른 관심사다. 오늘 네 {@code ORIGIN_*} 는 제어문자가 없고 240 코드포인트 아래라
     * {@code sanitizeForLog} 아래에서 <b>항등</b>이고 <b>관측 차이가 0</b> 이다. 둘이 갈리는
     * 한 값으로 둘을 하면 분기 키가 로그 가공에 의존하게 된다.
     */
    private static String recoveryFor(String origin, String safeOrigin) {
        if (ProfanityFilterFactory.ORIGIN_SOURCE.equals(origin)
                || ProfanityFilterFactory.ORIGIN_PROBE.equals(origin)) {
            return "전량은 ADMIN 조회 /api/v1/chat/filter-words 로 받아 다시 센다";
        }
        if (ProfanityFilterFactory.ORIGIN_ADMIN.equals(origin)) {
            // 조회 포인터를 <같이> 남긴다. 잘리는 꼬리가 보통 요청 항목이 아니다 —
            // warningEntries 가 words 루프를 먼저, whitelist 루프를 나중에 돌므로
            // kind=PROFANITY 추가에서 요청 항목의 경고는 머리 블록에 들고 꼬리는 화이트리스트
            // 경고로 채워진다. 그 행들은 조회로 전부 받을 수 있다. 요청 항목이 꼬리인 것은
            // kind=WHITELIST 추가뿐이라, 조회 포인터와 "보낸 값으로 확인" 을 같이 준다.
            return "전량은 ADMIN 조회 /api/v1/chat/filter-words 로 받되, 보낸 항목은 아직 커밋 "
                    + "전이라 조회에 없다 — 그 항목이 잘렸는지는 보낸 값으로 확인";
        }
        // ORIGIN_SNAPSHOT 과 모르는 출처가 같이 받는 분기다 — 런타임에 가장 자주 떨어지는
        // 것이 전자다(전 Pod · 교체마다). 조회로 못 보는 것은 둘 다 참이라 문구가 맞다.
        // 보간은 다듬은 값으로 한다.
        return "이 집합은 ADMIN 조회로 못 본다(출처=" + safeOrigin + ") — 그 출처를 직접 확인";
    }

    /** {@link ProfanityDictionary#visible} 을 그대로 쓴다 — 복구가 원문을 요구하므로 묶지 않는다. */
    @Override
    public String describe(String word) {
        return ProfanityDictionary.visible(word);
    }
}
