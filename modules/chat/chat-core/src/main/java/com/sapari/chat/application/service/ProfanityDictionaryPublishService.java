package com.sapari.chat.application.service;

import java.util.Set;

import com.sapari.chat.application.port.ProfanityDictionarySnapshotPublisher;
import com.sapari.chat.domain.model.FilterWordKind;
import com.sapari.chat.domain.repository.ChatFilterWordRepository;

import lombok.RequiredArgsConstructor;

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
@RequiredArgsConstructor
public class ProfanityDictionaryPublishService {

    private final ChatFilterWordRepository repository;
    private final ProfanityDictionarySnapshotPublisher snapshotPublisher;

    /**
     * 정본을 그대로 한 장 떠서 발행한다.
     *
     * <p>두 질의로 읽는다. 한 트랜잭션으로 묶지 않는 이유는 그 사이에 바뀐 쪽이 있어도 <b>다음
     * 발행이 덮기</b> 때문이다 — 사전을 고치는 경로가 전부 발행으로 끝나므로 어긋남이 한 회차를
     * 넘겨 살아남지 않는다. 반대로 묶으면 부팅 러너가 읽기 트랜잭션을 들고 Redis 를 치게 된다.
     *
     * @return 새로 붙은 버전
     */
    public long publishFromSource() {
        Set<String> words = repository.findWords(FilterWordKind.PROFANITY);
        Set<String> whitelist = repository.findWords(FilterWordKind.WHITELIST);
        return snapshotPublisher.publish(words, whitelist);
    }
}
