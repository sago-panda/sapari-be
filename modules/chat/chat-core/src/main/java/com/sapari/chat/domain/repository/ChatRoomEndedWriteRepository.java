package com.sapari.chat.domain.repository;

import java.util.UUID;

/**
 * 종료 마커({@code chat:room:{roomId}:ended}) <b>해제</b> 포트. <b>블로킹</b> — 호출자가 live-app(MVC)이다.
 *
 * <p>기록과 조회는 리액티브 어댑터({@code ChatRoomEndedRepository})가 하고 streaming-app 이 쓴다.
 * 해제만 이쪽에 있는 이유는 <b>해제에 필요한 판단이 그 앱에 없기 때문</b>이다 — 아래.
 *
 * <p><b>이 마커의 결함이 정본 L20 이다</b>: 진위 검증도 해제 경로도 없어서, 오발행 한 건이 진행 중인
 * 방을 TTL(30분)만큼 채팅 불능으로 만든다. 오발행이 사람 실수만도 아니다 — 방치된 Live 를 정리하는
 * 배치도 같은 신호를 발행하므로 방 단위 오판이 자동으로 생길 수 있다. 기록 어댑터의 로그가
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
}
