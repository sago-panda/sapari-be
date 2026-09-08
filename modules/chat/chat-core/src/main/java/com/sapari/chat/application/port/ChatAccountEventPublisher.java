package com.sapari.chat.application.port;

import java.util.UUID;

/**
 * 계정 조치를 전 Pod에 알린다. <b>블로킹</b> — 강퇴 REST를 받는 앱(live-app)에서 불린다.
 *
 * <p>강퇴 발행({@code ChatKickEventPublisher})과 갈라 두는 이유는 <b>범위가 다르기 때문</b>이다. 강퇴는
 * 그 방 채널로 나가 그 방 세션만 닿고, 밴은 계정 전체라 방을 모르는 채로 모든 Pod에 닿아야 한다.
 *
 * <p><b>실패는 전파한다.</b> 발행하지 못한 밴은 다른 방에 열려 있는 세션에 닿지 못한 밴이고, 그것을
 * 성공이라 부르면 그 사람은 밴이 걸린 뒤에도 계속 말한다. 다만 전송 경로가 메시지마다 밴을 다시 보므로
 * 발행이 빠져도 <b>첫 발화에서</b> 막힌다 — 이 발행이 닫는 것은 "조용히 앉아 있는 세션"이다.
 */
public interface ChatAccountEventPublisher {

    void publishBanned(UUID userId);
}
