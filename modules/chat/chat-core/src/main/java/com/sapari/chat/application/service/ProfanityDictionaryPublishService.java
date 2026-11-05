package com.sapari.chat.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

import com.sapari.chat.application.port.ProfanityDictionarySnapshotPublisher;
import com.sapari.chat.application.port.ProfanityFilterFactory;
import com.sapari.chat.domain.model.FilterWordKind;
import com.sapari.chat.domain.repository.ChatFilterWordRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 정본을 읽어 전파용 스냅샷으로 내보낸다 — 정본과 함대 사이의 유일한 다리다.
 *
 * <p><b>발행이 트랜잭션 밖이다.</b> 쓰기 트랜잭션 안에서 발행하면 롤백된 단어가 함대에 깔리고,
 * 그 상태는 정본에 흔적이 없어 어디서 왔는지 찾을 수 없다. 그래서 쓰기(
 * {@link ChatFilterWordSeedRecorder})와 발행이 다른 빈이고 호출 순서가 계약이다.
 *
 * <p><b>부팅마다 발행한다.</b> 멱등이고(정본을 읽어 덮어쓴다) 받는 쪽 재빌드가 0.54 ms 라
 * 비용이 없는데, 그 대신 "스냅샷이 없다/낡았다" 는 상태가 배포 한 번으로 스스로 정리된다.
 * 발행을 변경 시점에만 하면 스냅샷이 사라졌을 때(키 유실·다른 환경에서 지움) 되돌릴 경로가
 * 운영자의 손뿐이다.
 *
 * <p>⚠️ <b>그래서 발행 트리거가 정확히 셋이고 주기 재발행이 없다.</b> live-app 부팅
 * ({@code ChatDictionaryBootstrap}) · ADMIN 추가 · ADMIN 제거 — 이 셋뿐이다. 받는 쪽은 분마다
 * 버전을 확인하지만({@code ProfanityDictionaryRefreshHandler}) <b>그것은 발행을 유발하지
 * 않는다</b>: 스냅샷이 사라진 상태에서 분마다 확인해도 읽을 것이 없고, 함대는 번들 사전으로
 * 돌아간 채 조용히 돈다.
 *
 * <p>주기 재발행을 두지 않은 것은 의도다. 앱이 여럿이라 스케줄러에 분산 잠금(ShedLock 류)이
 * 필요한데, 그 배선의 값은 "아무도 쓰지 않고 아무도 재배포하지 않는 구간" 에서만 나온다 —
 * 그 구간에서 함대가 쓰는 사전은 번들이고, 번들은 비어 있지 않다 — 그것을 강제하는 부팅은
 * <b>streaming-app</b> 이다({@code ChatBeansConfig} 의 {@code profanityFilterSource} 가
 * {@code ProfanityDictionary.load} 를 부르고, {@code @Bean} 팩토리라 던지면 컨텍스트 refresh 가
 * 실패한다). 윗단락이 발행 트리거로 live-app 부팅을 말하고 있어 그쪽으로 읽히는데, 그 독법에서는
 * 이 전제가 약해진다 — live-app 이 죽은 채 함대만 돌 수 있다. 함대 자신의 부팅이 강제하므로
 * 그 경우에도 번들은 비어 있지 않다.
 * 즉 잃는 것은 <b>최근 ADMIN 변경</b>이고 되찾는 비용은 <b>쓰기 한 번</b>이다. 그 교환이
 * 뒤집히면(사전이 커져 번들과 정본의 차이가 운영상 유의미해지면) 그때 넣을 것이고, 그 판단의
 * 입력은 "번들과 테이블의 항목 수 차이" 다.
 */
@Slf4j
@RequiredArgsConstructor
public class ProfanityDictionaryPublishService {

    private final ChatFilterWordRepository repository;
    private final ProfanityDictionarySnapshotPublisher snapshotPublisher;
    private final ProfanityFilterFactory filterFactory;

    /**
     * 재시도 상한. 경합이 사람 타이핑 빈도(관리자 쓰기)와 롤링 배포(부팅 발행)뿐이라 두 번째
     * 시도에서 거의 끝난다. 무한으로 두지 않는 이유는 버전 키가 밖에서 계속 움직이는 상황
     * (스크립트 밖에서 누가 그 키를 쓰고 있는 경우)에서 이 스레드가 영구히 돌지 않게 하는 것이다.
     */
    private static final int MAX_ATTEMPTS = 5;

    /**
     * 로그 한 줄의 상한 — 수는 {@link ProfanityFilterFactory#LOG_ENTRY_LIMIT} 가 갖는다.
     *
     * <p>⚠️ 상한이 없을 때 전량이 걸리면 한 줄이 <b>35,966자</b>였다(측정: 욕설 1,078 ·
     * 화이트리스트 38). 평시에는 0 건이라 안 드러나는데, 접기 평면을 바꾸는 변경(예:
     * {@code normalizeEntry} 가 NFKC 로 가는 것)이 들어오면 <b>모든 행이 불일치</b>가 되어
     * 발행 1회당 그 줄이 나간다.
     *
     * <p>같은 상한이 적재 경고 쪽에도 필요하다({@code DictionaryProfanityFilterFactory}).
     * 레이어가 달라 상수를 공유할 자리가 포트뿐이라 거기에 뒀다 — 각자 20 을 적어 두면 한쪽만
     * 바뀌는 드리프트가 되고, 이 브랜치가 그 모양을 네 번 밟았다.
     */
    private static final int REPORT_LIMIT = ProfanityFilterFactory.LOG_ENTRY_LIMIT;

    /** 문에 물어볼 때 실패 메시지에 실릴 출처 — 이 호출은 진단이고 집행 사전을 만들지 않는다. */
    private static final String DOOR_PROBE_ORIGIN = "chat:filter-word:probe";

    /**
     * 정본을 그대로 한 장 떠서 발행한다.
     *
     * <p><b>버전을 먼저 집고, 정본을 읽고, "그 번호가 그대로일 때만" 쓴다.</b> 조건이 깨지면
     * <b>정본을 다시 읽어</b> 재시도한다 — 같은 본문으로 다시 던지면 낡은 본문을 밀어 넣는 것이라
     * 고치려던 결함을 그대로 재현한다. 그래서 재시도가 이 루프 안에 있고 어댑터에 없다: 다시
     * 읽어야 하는 것이 정본이고, 그것을 쥔 쪽이 여기다.
     *
     * <p>⚠️ 한때 여기 "한 트랜잭션으로 묶지 않는 이유는 그 사이에 바뀐 쪽이 있어도 <i>다음
     * 발행이 덮기</i> 때문이다 — 어긋남이 한 회차를 넘겨 살아남지 않는다" 고 적혀 있었다.
     * <b>그 문장이 거짓이었다.</b> "다음 발행이 덮는다" 가 성립하려면 나중에 발행한 쪽이 더 새
     * 본문을 들고 있어야 하는데, 버전은 나중 발행자가 이기고 본문은 먼저 읽은 쪽이 낡을 수
     * 있다(포트 javadoc 의 끼어들기 — 단어 Y 가 테이블에는 있고 함대에는 없는 상태가 다음 쓰기나
     * 부팅까지 남는다). 조건부 발행이 그 구멍을 닫는다.
     *
     * <p>여전히 한 트랜잭션으로 묶지 않는다. 묶으면 부팅 러너가 읽기 트랜잭션을 들고 Redis 를
     * 치게 되고, 그것으로 닫히는 것도 <b>이 구멍이 아니다</b> — 두 질의 사이의 불일치는 조건부
     * 발행이 재시도로 해소하고, 트랜잭션은 그 사이에 커밋된 쓰기를 숨겨 오히려 한 회차 늦춘다.
     *
     * @return 새로 붙은 버전
     * @throws IllegalStateException 상한까지 조건이 계속 깨졌을 때. 조용히 포기하면 호출자가
     *         발행됐다고 믿고 정본과 함대가 갈린다
     */
    public long publishFromSource() {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            // 순서가 계약이다 — 버전을 <읽기 전에> 집어야 그 번호가 "이 본문이 반영하는 시점"
            // 의 기준이 된다. 읽은 뒤에 집으면 그 사이의 발행을 못 보고 조건이 헛돈다.
            long expected = snapshotPublisher.currentVersion();
            Set<String> words = repository.findWords(FilterWordKind.PROFANITY);
            Set<String> whitelist = repository.findWords(FilterWordKind.WHITELIST);
            if (attempt == 1) {
                errorIfRowsAreDefective(words, whitelist);
            }

            OptionalLong version =
                    snapshotPublisher.publishIfUnchanged(expected, words, whitelist);
            if (version.isPresent()) {
                return version.getAsLong();
            }
            log.debug("사전 발행 재시도 — 그 사이에 다른 발행이 있었다 attempt={} expected={}",
                    attempt, expected);
        }
        throw new IllegalStateException(
                "사전 스냅샷 발행이 " + MAX_ATTEMPTS + "회 연속 다른 발행에 밀렸다 — "
                        + "정본과 함대가 갈린 채로 끝나지 않게 실패로 알린다. 버전 키를 "
                        + "스크립트 밖에서 쓰고 있는 경로가 있는지 확인할 것.");
    }

    /**
     * 정본에 <b>두 부류의 결함 행</b>이 있는지 본다 — 로그만 남긴다.
     *
     * <p>이 티켓은 쓰기 경로가 접은 값을 저장하게 고쳤고({@code ManageFilterWordsService.fold})
     * 접기가 고정점임을 테스트로 못 박았다. 그 둘은 <b>앞으로</b> 그런 행이 생기지 않게 한다.
     * 이미 있는 행에는 둘 다 닿지 않는다 — 이 브랜치 전의 쓰기 경로는 원문을 저장했고,
     * {@code CHAT_PROFANITY_WORDS} 로 들어간 항목도 같은 모양일 수 있다.
     *
     * <p>⚠️ <b>두 부류는 포함관계가 아니라 직교다.</b> 한때 이 자리가 "접기 불일치" 하나로 둘을
     * 덮는다고 보고 한 문장으로 단정했는데, 테스트가 그것을 깼다. 실측(글자 여섯 — NBSP ·
     * ZWSP · 워드조이너 · BOM · 한글 채움문자 · 공백 — × 선행·후행·내부 = 18칸, rejected=15 ·
     * passed=3):
     * <pre>
     *   선행 BOM      접기가 뗀다      문 통과   → 저장값 ≠ 집행값 (그림자)
     *   후행 BOM      접기가 보존한다   문 거부   → 저장값 = 집행값 (동결)
     *   내부 ZWSP·BOM 접기가 보존한다   문 거부   → 저장값 = 집행값 (동결)
     * </pre>
     * 즉 <b>동결 행은 접기 불일치가 아니다</b> — 저장값과 집행값이 같고, 문이 그 값을 거부할
     * 뿐이다. 그래서 두 축을 각자 잰다.
     *
     * <p><b>부류 1 — 문이 거부한다(동결).</b> {@code of()} 가 {@code strip} 뒤에 거부하므로,
     * 접은 뒤에도 보이지 않는 글자가 남거나 예산을 넘는 행이 여기다. (1) 전 Pod 이 스냅샷 교체를
     * 거부하고 (2) 다음 ADMIN 쓰기가 전부 409 다. 접기가 그 값을 보존하므로 <b>같은 값을 그대로
     * 보내면 API 로 지울 수 있다</b> — 복구가 수동 SQL 이 아니다. 판정은 <b>문에게 전체 집합을
     * 한 번 물어서</b> 한다: 문이 이미 그 일을 하고 어느 항목인지까지 메시지에 담으므로, 여기서
     * 행마다 다시 묻는 것은 트라이를 1,116번 세우는 것이고 판정을 재구현하는 것이다.
     *
     * <p><b>부류 2 — 문이 통과시킨다(그림자 행).</b> 접기가 그 글자를 전부 떼는 행이다. 함대는
     * 멀쩡히 집행하는데 {@code uk (kind, word)} 가 <b>원문 기준</b>이라 테이블이 {@code 시발} 과
     * {@code <BOM>시발} 을 같이 들 수 있고, {@code remove("시발")} 은 <b>한 행만</b> 지우고
     * {@code changed=true}·버전 상승·성공 응답을 낸다. 그런데 남은 그림자 행이 같은 평면으로
     * 접히므로 <b>그 단어는 계속 막힌다</b> — 운영자가 "풀었는데 여전히 걸린다" 를 본다.
     * {@code fold} javadoc 이 경계한 "이미 그 상태였다 인데 실제로는 집행 중" 의 반대 방향이고,
     * 이쪽은 삭제가 <i>성공으로 보이는</i> 점이 더 나쁘다. 복구는 그림자 행을 직접 지우는 것이다.
     *
     * <p>⚠️ <b>이 로그는 항목 값을 싣는다 — 이 배치의 다른 두 자리와 반대이고 의도다.</b>
     * {@code ManageFilterWordsService.publishOrLogLoss} 와 {@code ChatBeansConfig} 의 설정 WARN
     * 은 값을 빼고 개수만 싣는다(그 둘은 <i>정상 경로</i>라 상시 나가고, 값이 로그에 쌓이는 것
     * 말고 얻는 것이 없다). 이쪽은 비정상 경로이고 복구 수단이 "그 행을 지목하기" 라 <b>정확한
     * 값이 없으면 복구가 불가능하다</b>. 보이지 않는 글자만 {@code \\uXXXX} 로 바꾸므로
     * ({@code ProfanityFilterFactory.describe}) 되돌릴 수 있는 표기이고, 가리는 것이 목적이 아니다.
     *
     * <p><b>막지 않는다.</b> 여기서 예외를 올리면 그 상태에서 발행이 아예 못 돌아 부류 1 의
     * 동결이 한 겹 더 굳는다 — 쓰기도 부팅 발행도 전파를 못 내보낸다. (부팅 자체는 뜬다:
     * {@code ChatDictionaryBootstrap} 이 발행 실패를 받아 ERROR 만 남긴다. 한때 이 괄호가
     * "부팅까지 실패한다" 였는데 거짓이고, 그 자리에서 진짜로 부팅을 죽이는 것은 같은 러너의
     * <b>번들 로드</b>다 — 두 축이 섞여 적혀 있었다.)
     *
     * <p>첫 시도에서만 센다. 재시도는 같은 정본을 다시 읽는 것이라 결과가 같고, 경합이 심한
     * 구간에서 같은 ERROR 를 다섯 번 찍을 이유가 없다.
     */
    private void errorIfRowsAreDefective(Set<String> words, Set<String> whitelist) {
        errorIfTheDoorRefusesTheSource(words, whitelist);
        errorIfShadowRowsExist(words, whitelist);
    }

    /** 부류 1 — 문에게 전체 집합을 한 번 묻는다. 어느 항목인지는 문의 메시지가 담는다. */
    private void errorIfTheDoorRefusesTheSource(Set<String> words, Set<String> whitelist) {
        try {
            filterFactory.from(DOOR_PROBE_ORIGIN, words, whitelist);
        } catch (IllegalStateException e) {
            log.error("정본이 사전 문을 통과하지 못한다 — 전 Pod 의 사전 교체가 거부되고 이후 모든 "
                            + "ADMIN 쓰기가 409 다. 문은 <첫 위반에서 즉시> 던지므로 아래 사유는 "
                            + "한 건이고 전체 건수는 알 수 없다 — 하나를 지우면 다음 발행이 다음 "
                            + "사유를 말한다. 접기가 그 값을 보존하는 행이면 같은 값을 그대로 보내 "
                            + "API(/api/v1/chat/filter-words)로 지울 수 있고, 그림자 보고에도 같이 "
                            + "뜨는 행이면(접기가 글자를 떼는 행) 삭제가 0행이므로 "
                            + "chat_filter_word 에서 직접 지워야 한다. 사유={}", e.getMessage());
        }
    }

    /**
     * 부류 2 — 접기가 저장값과 집행값을 갈라 놓은 행.
     *
     * <p>이쪽은 문이 통과시키므로 문에게 물어서는 찾을 수 없다. 접기 한 번씩이라 행당 비용이
     * 트라이가 아니고, 걸린 행만 렌더링한다.
     */
    private void errorIfShadowRowsExist(Set<String> words, Set<String> whitelist) {
        List<String> shown = new ArrayList<>();
        int total = collectShadowRows(FilterWordKind.PROFANITY, words, shown)
                + collectShadowRows(FilterWordKind.WHITELIST, whitelist, shown);
        if (total == 0) {
            return;
        }
        log.error("정본에 <그림자 행>이 있다 — 문은 통과하므로 집행은 정상인데, uk (kind, word) 가 "
                        + "원문 기준이라 접으면 같아지는 행이 둘 이상 있을 수 있다. 그러면 삭제 요청이 "
                        + "한 행만 지우고 changed=true·버전 상승·성공 응답을 내는데 남은 행이 같은 "
                        + "단어를 계속 집행한다. 복구는 chat_filter_word 에서 이 행을 직접 지우는 "
                        + "것이다. 전체 {}건 중 {}건: {}", total, shown.size(), capped(shown, total));
    }

    private int collectShadowRows(FilterWordKind kind, Set<String> stored, List<String> shown) {
        int total = 0;
        for (String word : stored) {
            if (word.equals(filterFactory.normalize(word))) {
                continue;
            }
            total++;
            if (shown.size() < REPORT_LIMIT) {
                shown.add(kind + ":" + filterFactory.describe(word));
            }
        }
        return total;
    }

    /**
     * ⚠️ 비교 대상이 {@code shown.size()} 와 <b>{@code total}</b> 이다 — 상한이 아니다.
     *
     * <p>한때 {@code shown.size() < REPORT_LIMIT} 였다. 그러면 그림자 행이 <b>정확히 20건</b>일 때
     * {@code 전체 20건 중 20건: … 외 더 있음} 이 나간다 — 당직자가 20건을 다 보고도 더 있다고 읽고
     * SQL 로 다시 센다. 테스트가 50건만 재서 그 경계가 분모에 없었다.
     */
    private static String capped(List<String> shown, int total) {
        String joined = String.join(" · ", shown);
        return shown.size() < total
                ? joined + " … 외 " + (total - shown.size()) + "건(상한 " + REPORT_LIMIT + "건)"
                : joined;
    }
}
