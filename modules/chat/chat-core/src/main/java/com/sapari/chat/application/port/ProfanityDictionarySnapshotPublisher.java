package com.sapari.chat.application.port;

import java.util.Set;

/**
 * 정본에서 읽은 사전을 전파용 스냅샷으로 내보낸다 — <b>live-app 쪽 포트</b>다.
 *
 * <p><b>왜 스냅샷이 필요한가.</b> 사전의 정본은 Postgres 인데 그것을 평가하는 앱
 * (streaming-app)에는 DataSource 가 없다 — {@code DataSourceAutoConfiguration} 을 통째로 빼고,
 * 그것을 {@code AutoConfigurationExclusionTest} 가 못 박는다. 그래서 정본을 읽을 수 있는 앱이
 * 읽어서, 읽을 수 없는 앱이 닿을 수 있는 자리에 놓아야 한다.
 *
 * <p><b>발행은 커밋 뒤에만.</b> 쓰기 트랜잭션 안에서 발행하면 롤백된 단어가 함대에 깔리고,
 * 그 상태는 정본에 흔적이 없어 어디서 왔는지 찾을 수 없다.
 *
 * <p><b>버전은 이 포트가 만들지 않는다 — 구현(정본 쪽 카운터)이 만든다.</b> 호출자가 번호를
 * 정하면 live-app Pod 가 둘일 때 같은 번호를 두 번 쓰거나 번호가 뒤로 갈 수 있고, 그러면
 * 받는 쪽의 단조 비교가 거짓을 지킨다.
 */
public interface ProfanityDictionarySnapshotPublisher {

    /**
     * 두 집합을 스냅샷 한 장으로 덮어쓴다. 전체 교체다 — 델타가 아니다.
     *
     * <p>전체인 이유: 받는 쪽의 재빌드가 1,078 항목에서 p50 0.54 ms 이고 사전 변경 빈도는 사람이
     * 타이핑하는 속도다. 델타는 적용 순서라는 두 번째 상태를 들여오는데, 그걸로 아끼는 것이
     * 0.54 ms 다.
     *
     * @return 새로 붙은 버전. 호출자가 로그에 남겨 "내가 밀어 넣은 것이 함대에 깔렸는가" 를
     *         받는 쪽 로그와 맞출 수 있어야 한다
     */
    long publish(Set<String> words, Set<String> whitelist);
}
