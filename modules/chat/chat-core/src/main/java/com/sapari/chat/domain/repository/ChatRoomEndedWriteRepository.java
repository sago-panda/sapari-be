package com.sapari.chat.domain.repository;

import java.util.UUID;

/**
 * 종료 마커({@code chat:room:{roomId}:ended}) <b>해제</b> 포트. <b>블로킹</b> — 호출자가 live-app(MVC)이다.
 *
 * <p>기록과 조회는 리액티브 어댑터({@code ChatRoomEndedRepository})가 하고 streaming-app 이 쓴다.
 * 해제만 이쪽에 있는 이유는 <b>해제에 필요한 판단이 그 앱에 없기 때문</b>이다 — 아래.
 *
 * <p><b>이 마커의 결함이 정본 L20 이다</b>: 진위 검증도 해제 경로도 없어서, 오발행 한 건이 진행 중인
 * 방을 마커 보관 기간만큼 채팅 불능으로 만든다. 다만 이 삭제로 닫히는 것은 <b>앱 밖에서 들어온
 * 발행</b>뿐이다 — 앱 경로의 오발행(배치의 방 단위 오판 포함)은 방을 먼저 {@code Ended} 로
 * 커밋하고 발행하므로 유스케이스의 LIVE 검증에 거부된다. 그 사실과 근거는
 * {@code ClearRoomEndedMarkerService} javadoc 에 있다. 기록 어댑터의 로그가
 * {@code (해제: DEL ...)} 를 찍어 둔 것이 그 때문이고, 그게 지금까지의 유일한 처방이었다.
 *
 * <p><b>진위 검증은 여기가 아니라 유스케이스에 있다.</b> chat 은 받은 Pub/Sub 신호가 진짜인지 확인할
 * 수단이 없지만, live 의 정본을 읽을 수 있는 앱은 있다 — 그래서 해제는 "그 방이 <b>실제로</b> 진행
 * 중인가" 를 live 정본에 묻고 나서만 한다({@code ClearRoomEndedMarkerService}). 즉 이 포트가 블로킹인
 * 것은 구현 편의가 아니라, 진위를 물을 수 있는 쪽이 그 스택이기 때문이다.
 *
 * <p>실패는 그대로 던진다 — 못 지운 마커는 그 방이 계속 막혀 있다는 뜻이다.
 */
public interface ChatRoomEndedWriteRepository {

    /**
     * 마커를 지운다 — <b>조건 없이</b>. 없는 키 삭제는 no-op 이라 몇 번을 불러도 안전하다.
     *
     * <p>존재를 먼저 묻지 않는다. 물으면 왕복이 둘이 되고 그 사이가 새 레이스가 되는데, 얻는 것은
     * "지울 게 없었다" 는 정보뿐이다. 그 정보는 유스케이스가 다른 방식으로 이미 갖는다 —
     * 진위 검증에서 방이 진행 중임을 확인했으므로, 마커가 없으면 이미 풀린 것이다.
     */
    void clearEnded(UUID roomId);

    /**
     * 방금 지운 마커를 <b>되돌려 놓는다</b> — 해제 중에 방이 실제로 끝난 것으로 드러났을 때만 쓴다.
     *
     * <p>기록 경로({@code ChatRoomEndedRepository.markEnded})와 같은 값·같은 만료를 써야 한다.
     * 다르게 쓰면 되돌린 마커가 원래보다 짧거나 길게 살아, 되돌리려던 상태와 다른 상태가 된다.
     *
     * <p>이것이 필요한 이유는 검증과 삭제 사이의 창이다. 방 조회에 행 잠금이 없어 그 사이에
     * {@code endLive} 가 커밋될 수 있고, 그러면 이 삭제가 <b>맞는 마커</b>를 지운다. 그 잔여
     * 상태가 특히 나쁜 것은 종료 신호를 놓친 pod 의 백스톱이 그 마커와 live 의 종료 키뿐이고(live
     * 키는 쓰기를 재시도하지 않아 없을 수 있다), 둘 다 비면 다시 세워 주는 경로가 없기 때문이다
     * ({@code Ended} 는 terminal 이라 신호가 다시 오지 않는다).
     *
     * <p>실패는 던진다 — 되돌리지 못한 상태가 이 분기가 막으려던 바로 그 상태다.
     */
    void markEndedAgain(UUID roomId);
}
