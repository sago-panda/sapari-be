package com.sapari.chat.port;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.sapari.chat.exception.ChatViewerCountUnavailableException;

/**
 * 방별 채팅 시청자 수 — 다른 도메인(live 목록)이 chat 키를 직접 읽지 않고 이 값을 얻는 유일한 길이다.
 *
 * <p>blocking — live-app(MVC)의 스케줄러 스레드에서 호출한다.
 *
 * <p>값은 <b>고유 사용자 수</b>다. 한 사람이 창을 여러 개 열어도 1명이고, 재등록이 끊긴 세션을 빼는 판정은
 * 채팅 접속 화면의 집계와 같다.
 */
public interface GetChatViewerCountsUseCase {

    /**
     * 반환 맵에는 요청한 모든 방이 들어 있고, 세션이 없는 방은 0 이다. 빈 입력에는 빈 맵.
     *
     * @throws ChatViewerCountUnavailableException 저장소 장애. 일부 방만 담긴 맵은 돌려주지 않는다 —
     *         빠진 방을 "0명" 으로 읽게 만들면 장애가 시청자 이탈처럼 보인다.
     */
    Map<UUID, Long> countViewers(Collection<UUID> roomIds);
}
