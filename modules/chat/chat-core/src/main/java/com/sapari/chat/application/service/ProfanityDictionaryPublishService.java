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
 * 그 구간에서 함대가 쓰는 사전은 번들이고, 번들은 비어 있지 않다(부팅이 그것을 강제한다).
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
                errorIfRowsCannotBeAddressed(words, whitelist);
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
     * 접기로 <b>가리킬 수 없는 행</b>이 정본에 있는지 본다 — 로그만 남긴다.
     *
     * <p>이 티켓은 쓰기 경로가 접은 값을 저장하게 고쳤고({@code ManageFilterWordsService.fold})
     * 접기가 고정점임을 테스트로 못 박았다. 그 둘은 <b>앞으로</b> 그런 행이 생기지 않게 한다.
     * 이미 있는 행에는 둘 다 닿지 않는다 — 이 브랜치 전의 쓰기 경로는 원문을 저장했고,
     * {@code CHAT_PROFANITY_WORDS} 로 들어간 항목도 같은 모양일 수 있다.
     *
     * <p><b>그 행 하나가 전파를 영구 동결시킨다.</b> 보이지 않는 글자가 든 항목은 사전 문에서
     * 떨어지므로 (1) 전 Pod 이 스냅샷 교체를 거부하고 (2) 다음 ADMIN 쓰기가 전부 409 가 되고
     * (3) 그 행을 지우려는 삭제 요청은 접혀서 다른 값을 찾으므로 <b>0행</b>이다. 운영자에게
     * 보이는 것은 "성공했다는 응답과 올라가는 버전, 그런데 아무것도 안 막힌다" 다.
     *
     * <p><b>막지 않는다.</b> 여기서 예외를 올리면 그 상태에서 발행이 아예 못 돌아 동결이
     * 한 겹 더 굳는다(부팅까지 실패한다). 하는 일은 <b>무엇을 지워야 하는지</b>를 코드 포인트로
     * 찍어 주는 것이다 — 문제가 보이지 않는 글자라서, 평문으로 적으면 로그에서도 안 보인다.
     *
     * <p>첫 시도에서만 센다. 재시도는 같은 정본을 다시 읽는 것이라 결과가 같고, 경합이 심한
     * 구간에서 같은 ERROR 를 다섯 번 찍을 이유가 없다.
     */
    private void errorIfRowsCannotBeAddressed(Set<String> words, Set<String> whitelist) {
        List<String> unreachable = new ArrayList<>();
        collectUnreachable(FilterWordKind.PROFANITY, words, unreachable);
        collectUnreachable(FilterWordKind.WHITELIST, whitelist, unreachable);
        if (unreachable.isEmpty()) {
            return;
        }
        log.error("정본에 <접기로 가리킬 수 없는 행>이 {}건 있다 — 이 행 하나로 전 Pod 의 사전 "
                        + "교체가 거부되고 이후 모든 ADMIN 쓰기가 409 가 되며, 그 행을 지우려는 "
                        + "요청은 접혀서 0행이 된다(이 브랜치 전의 쓰기 경로가 원문을 저장했다). "
                        + "복구는 chat_filter_word 에서 아래 행을 직접 지우는 것이다 — 코드 포인트는 "
                        + "U+XXXX 로 적었다: {}",
                unreachable.size(), unreachable);
    }

    private void collectUnreachable(FilterWordKind kind, Set<String> stored, List<String> out) {
        for (String word : stored) {
            if (!word.equals(filterFactory.normalize(word))) {
                out.add(kind + ":" + describe(word));
            }
        }
    }

    /** 코드 포인트로 적는다 — 평문으로 적으면 BOM·ZWSP 가 로그에서도 안 보인다. */
    private static String describe(String word) {
        StringBuilder out = new StringBuilder();
        word.codePoints().forEach(cp -> out.append(String.format("U+%04X ", cp)));
        return out.toString().strip();
    }
}
