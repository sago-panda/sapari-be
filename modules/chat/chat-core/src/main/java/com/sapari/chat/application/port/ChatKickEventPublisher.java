package com.sapari.chat.application.port;

import java.util.UUID;

/**
 * 강퇴 사실을 모든 Pod에 알리는 포트. <b>블로킹</b> — 호출자가 api-app(MVC)이다.
 *
 * <p>{@link ChatBroadcaster}와 <b>같은 채널을 쓰지만 다른 포트</b>다. 그쪽은 리액티브 스택에서 채팅
 * 봉투를 주고받는 통로이고(구독까지 포함한다), 이쪽은 강퇴 봉투 한 종류를 내보내기만 한다. 채널만 같고
 * 스택도 방향도 다르다.
 *
 * <p><b>발행에 성공해야 강퇴가 성립한다.</b> 명단에는 올랐는데 이 발행이 실패하면 당사자는 끊기지 않고
 * 그대로 남아 계속 글을 쓴다 — 다음 전송의 강퇴 조회에서야 걸리는데 그 조회는 장애 시 통과하는 쪽이다.
 * 그래서 실패를 성공이라 부르지 않고 전파한다. 재시도는 안전하다: 명단 추가도 발행도 멱등이고,
 * 받는 쪽은 이미 숨긴 사람을 다시 숨기거나 이미 닫힌 세션을 또 닫아도 아무 일이 없다.
 */
public interface ChatKickEventPublisher {

    void publishKicked(UUID roomId, UUID kickedUserId);
}
