package com.sapari.chat.domain.repository;

import java.util.Set;
import java.util.UUID;

import com.sapari.chat.domain.model.FilterWordKind;
import com.sapari.chat.port.BlockingChat;

/**
 * 욕설 사전의 <b>정본</b> — {@code live_schema.chat_filter_word}.
 *
 * <p><b>왜 정본이 DB 인가.</b> 전에는 번들 {@code .txt} 가 사실상 정본이었고, 그래서 단어 하나를
 * 바꾸려면 배포가 필요했다 — 방송 중에 새 우회 표현이 돌기 시작하면 그날은 손쓸 방법이 없다.
 * 그 단방향을 끊는 것이 이 포트의 존재 이유다. 번들 파일은 이제 <b>시드의 원본</b>이고(테이블이
 * 비었을 때 한 번 들어간다) 그 뒤로는 테이블이 저자다.
 *
 * <p><b>이 포트를 가진 앱은 live-app 뿐이다.</b> streaming-app 에는 DataSource 가 없고
 * ({@code DataSourceAutoConfiguration} 을 통째로 뺀다) 그것을 테스트가 못 박고 있다. 그래서
 * 전파는 이 포트가 아니라 Redis 스냅샷으로 간다 — 이 포트는 스냅샷을 만드는 쪽에만 있다.
 *
 * <p><b>두 쓰기 모두 멱등이다.</b> 이미 있는 단어를 더하거나 없는 단어를 빼는 것은 오류가 아니라
 * 무동작이다. 관리자가 같은 단어를 두 번 누르는 것은 평범한 일이고, 그것이 500 이 되면 운영자는
 * "이미 막혀 있다" 와 "쓰기가 실패했다" 를 구분할 수 없다. 반환값이 그 구분을 준다.
 */
@BlockingChat
public interface ChatFilterWordRepository {

    /**
     * 사전 쓰기를 이 트랜잭션이 끝날 때까지 직렬화한다. 트랜잭션 안에서만 부른다.
     *
     * <p>검사가 두 집합 전체를 보므로(제거는 "욕설이 남는가", 추가는 두 kind 를 가로지르는 규칙)
     * 동시 쓰기 둘이 각자 통과하고 합친 결과만 어길 수 있다. 읽기 전에 이것을 잡으면 뒤쪽은 앞쪽의
     * 커밋을 보고 검사한다.
     */
    void lockForWrite();

    /** 그 종류의 항목 전체. 스냅샷 발행과 시드 판정이 쓴다. */
    Set<String> findWords(FilterWordKind kind);

    /**
     * 항목을 더한다.
     *
     * @return 실제로 새로 들어갔는가. {@code false} 는 이미 있었다는 뜻이고 오류가 아니다
     */
    boolean add(FilterWordKind kind, String word, UUID createdBy);

    /**
     * 항목을 뺀다. 행 DELETE 다 — 비활성 표시를 두지 않는다. 이 테이블의 쓰임이 "지금 무엇을
     * 막는가" 하나뿐이라, 지난 항목을 남기면 읽는 쪽마다 필터 조건이 필요해진다.
     *
     * @return 실제로 지워졌는가. {@code false} 는 없었다는 뜻이고 오류가 아니다
     */
    boolean remove(FilterWordKind kind, String word);

    /**
     * 테이블에 항목이 하나도 없는가 — <b>시드 판정</b> 전용이다.
     *
     * <p>"비었을 때만" 이어야 한다. 매 부팅에 번들을 다시 밀어 넣으면 운영자가 뺀 단어가 배포마다
     * 되살아나고, 그러면 {@link #remove} 가 작동하지 않는 것과 같다.
     */
    boolean isEmpty();
}
