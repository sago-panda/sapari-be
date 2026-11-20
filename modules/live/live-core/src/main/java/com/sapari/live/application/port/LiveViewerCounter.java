package com.sapari.live.application.port;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * 방별 시청자 수(공개 목록 순위 기준). 값의 출처는 채팅 접속(고유 사용자)이고, 어댑터는 live-app 이 chat-api 포트로 잇는다.
 *
 * <p><b>live-core 가 chat-api 를 직접 물지 않는 이유</b>: chat-core 가 이미 live-api 를 읽어(방 상태 확인) 두 도메인이
 * 서로를 의존하는 순환이 되고, ArchUnit 이 그 순환을 막는다. 그래서 이 포트를 live 가 소유하고 조립은 앱이 한다.
 */
public interface LiveViewerCounter {

    /**
     * 요청한 모든 방을 담아 돌려준다(세션이 없으면 0).
     *
     * @throws ViewerCountUnavailableException 출처를 읽지 못했다. 일부만 담긴 맵은 돌려주지 않는다.
     */
    Map<UUID, Long> countViewers(Collection<UUID> roomIds);
}
