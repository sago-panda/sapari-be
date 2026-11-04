package com.sapari.chat.application.service;

import java.util.OptionalLong;
import java.util.Set;

import com.sapari.chat.application.port.ProfanityDictionarySnapshotPublisher;
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
 */
@Slf4j
@RequiredArgsConstructor
public class ProfanityDictionaryPublishService {

    private final ChatFilterWordRepository repository;
    private final ProfanityDictionarySnapshotPublisher snapshotPublisher;

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
}
