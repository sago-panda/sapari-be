package com.sapari.chat.port;

import com.sapari.chat.command.AddFilterWordCommand;
import com.sapari.chat.command.ListFilterWordsCommand;
import com.sapari.chat.command.RemoveFilterWordCommand;
import com.sapari.chat.view.FilterWordsView;

/**
 * 욕설 사전을 운영자가 고치는 포트 — blocking. live-app(MVC)에서 호출한다.
 *
 * <p><b>셋을 한 포트에 둔 이유.</b> 이 저장소는 보통 엔드포인트 하나에 유스케이스 하나인데,
 * 여기 셋은 같은 자원에 대한 같은 권한(ADMIN)이고 <b>같은 쓰기 계약</b>을 공유한다 —
 * 선검사 → 쓰기 → 발행. 셋으로 가르면 그 계약이 세 서비스에 복제되고, 하나만 고쳐질 때
 * 어긋난 쪽이 조용히 정본과 함대를 벌린다.
 *
 * <p><b>쓰기는 발행으로 끝난다.</b> 정본만 고치고 발행하지 않으면 함대가 그 변경을 영원히 모른다
 * (주기 확인이 버전을 보므로, 버전이 안 오르면 아무 일도 일어나지 않는다). 그래서 두 쓰기 메서드가
 * 버전을 돌려준다 — 운영자가 자기 변경이 함대에 깔릴 번호를 그 자리에서 받는다.
 */
@BlockingChat
public interface ManageFilterWordsUseCase {

    /**
     * 항목을 더한다. ADMIN 전용.
     *
     * <p><b>멱등이다.</b> 이미 있는 단어를 다시 더하는 것은 오류가 아니라 무동작이다 — 관리자가
     * 같은 버튼을 두 번 누르는 것은 평범한 일이고, 그것이 500 이 되면 "이미 막혀 있다" 와
     * "쓰기가 실패했다" 를 구분할 수 없다. 그래도 발행은 한다: 멱등 호출에서 발행을 건너뛰면
     * 스냅샷이 유실된 상태를 되돌릴 경로가 하나 줄어든다.
     *
     * <p><b>검사는 이 단어가 아니라 <i>결과 사전 전체</i>에 걸린다.</b> 새 단어만 보면 테이블에
     * 이미 있는 규칙 위반 행 하나가 전파를 통째로 멈출 수 있다 — 쓰기는 통과해 201 이 나가고,
     * 모든 Pod 이 스냅샷을 읽다가 그 옛 행에 걸려 교체를 거부한다. 운영자는 201 을 받고 목록에서
     * 자기 단어를 보는데 어느 Pod 도 그것을 집행하지 않는다. 결과 전체를 검사하면 그 상태가
     * <b>문제 행의 이름과 함께</b> 첫 쓰기에서 거부로 나온다.
     */
    FilterWordChange add(AddFilterWordCommand command);

    /**
     * 항목을 뺀다. ADMIN 전용.
     *
     * <p>없는 단어를 빼는 것도 성공이다 — 추가와 같은 이유로 멱등이다.
     *
     * <p><b>추가와 달리 전체 검사를 지나지 않는다.</b> 제거는 새 위반을 만들 수 없고(실측: 번들
     * 전수 1,116건 단일 제거 중 새 위반 0건), 전체 검사를 걸면 <b>복구가 자기 자신에게 막힌다</b> —
     * 위반이 둘 이상이면 하나씩 지우는 요청이 전부 "남은 것 때문에" 거부되고, 이 API 가 한 번에
     * 하나를 지우므로 어느 것도 못 지운다. 그리고 둘 이상이 그 시나리오의 기본값이다(규칙을
     * 조이면 범위 밖 행이 한꺼번에 여럿 위반이 된다).
     *
     * <p>막는 것은 하나다 — <b>마지막 욕설</b>. 사전이 비면 마스킹이 조용히 꺼지므로 400 이다.
     * 제거 후에도 정본이 문을 통과하지 못하면 거부가 아니라 <b>경고 로그</b>다: 거부하면 복구가
     * 막히고, 조용히 넘기면 운영자가 복구가 끝났다고 믿는다.
     */
    FilterWordChange remove(RemoveFilterWordCommand command);

    /** 사전 전체. ADMIN 전용 — 읽기가 있어야 삭제가 쓸 수 있다. */
    FilterWordsView list(ListFilterWordsCommand command);

    /**
     * 쓰기 결과.
     *
     * @param changed 정본이 실제로 바뀌었는가. {@code false} 는 이미 그 상태였다는 뜻이고
     *                오류가 아니다 — 운영자가 "내 요청이 무언가를 바꿨는지" 를 알아야 반복 클릭과
     *                실패를 구분할 수 있다
     * @param version 이 호출이 발행한 스냅샷 버전. 받는 쪽 로그의 {@code version=} 과 맞춰 보면
     *                변경이 함대에 깔렸는지를 확인할 수 있다
     */
    record FilterWordChange(boolean changed, long version) {
    }
}
