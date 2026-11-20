package com.sapari.chat.application.port;

import java.util.OptionalLong;
import java.util.Set;

import com.sapari.chat.port.BlockingChat;

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
@BlockingChat
public interface ProfanityDictionarySnapshotPublisher {

    /**
     * 지금 발행돼 있는 버전. 한 번도 발행되지 않았으면 {@code 0} 이다.
     *
     * <p>호출자가 <b>정본을 읽기 전에</b> 이것을 집고, 읽은 뒤 {@link #publishIfUnchanged} 에
     * 그 값을 되넘긴다 — 그 사이에 다른 발행이 끼어들었는지를 가리는 기준이다.
     */
    long currentVersion();

    /**
     * 두 집합을 스냅샷 한 장으로 덮어쓴다 — <b>그 사이에 아무도 발행하지 않았을 때만</b>.
     * 전체 교체다(델타가 아니다).
     *
     * <p>전체인 이유: 받는 쪽의 재빌드가 1,078 항목에서 p50 0.54 ms 이고 사전 변경 빈도는 사람이
     * 타이핑하는 속도다. 델타는 적용 순서라는 두 번째 상태를 들여오는데, 그걸로 아끼는 것이
     * 0.54 ms 다.
     *
     * <p>⚠️ <b>조건이 붙은 이유 — 이것 없이는 발행이 서로를 덮는다.</b> 본문은 호출자가 정본에서
     * 읽은 값이고 그 읽기는 이 호출 <i>밖</i>에서 일어난다. 한때 이 포트가 조건 없는
     * {@code publish} 였고, 그때 이 끼어들기가 성립했다:
     * <pre>
     *   A: 단어 X 커밋 → 정본 읽어 {X} 손에 듦
     *   B:                 단어 Y 커밋 → {X,Y} 읽고 발행 v5
     *   A: 발행 v6, 본문 {X}        ← v6 이 최신이라 함대가 Y 없는 사전을 집행한다
     * </pre>
     * Y 는 테이블에 있어 관리자 목록에 보이는데 어느 Pod 도 그것을 막지 않고, 발행은 쓰기가
     * 있을 때만이라 <b>다음 쓰기나 부팅까지</b> 어긋난 채로 남는다. 롤링 배포 중에 단어를
     * 하나 넣으면 닿는다(부팅마다 발행이 나가므로).
     *
     * <p>조건이 서면 그 조합이 사라진다. 성공한 발행은 "내 버전 집기와 쓰기 사이에 다른 발행이
     * 없었다" 를 보장하고, 정본 읽기가 그 창 안에 있다. 그래서 <b>가장 높은 버전의 본문은 그
     * 읽기 전에 커밋된 모든 쓰기를 담는다</b> — 읽기 뒤에 커밋한 쓰기는 자기 발행에서 더 높은
     * 버전을 들고 오고, 그 발행은 이 쓰기 뒤에 일어난다(그 전이면 이쪽 조건이 깨진다).
     *
     * @param expectedVersion {@link #currentVersion()} 으로 집어 둔 값
     * @return 새로 붙은 버전. <b>비어 있으면</b> 그 사이에 다른 발행이 있었다는 뜻이고,
     *         호출자가 정본을 <b>다시 읽어</b> 재시도해야 한다 — 같은 본문으로 다시 던지면
     *         낡은 본문을 밀어 넣는 것이라 고치려던 결함을 그대로 재현한다
     */
    OptionalLong publishIfUnchanged(long expectedVersion, Set<String> words, Set<String> whitelist);
}
