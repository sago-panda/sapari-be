package com.sapari.chat.application.port;

import com.sapari.chat.domain.rule.ProfanityFilter;
import com.sapari.chat.port.InMemoryChat;

/**
 * 지금 유효한 필터를 <b>갈아 끼우는</b> 포트 — {@link ProfanityFilterSource} 의 반대 역할이다.
 *
 * <p>둘을 가른 이유: 읽는 쪽은 메시지마다 불리는 핫패스({@code SendChatService})이고 쓰는 쪽은
 * 사전이 바뀔 때만 불린다. 한 포트에 합치면 전송 경로가 교체 메서드를 손에 들게 되고, 그건
 * 그 경로가 가져야 할 권한이 아니다 — 같은 객체이고 역할이 둘이다.
 *
 * <p>구현은 하나({@code SwappableProfanityFilter})이고 이 앱이 두 포트 타입으로 같은 빈을 준다.
 *
 * <p><b>필터는 계속 불변이다.</b> 설치는 <i>참조</i>를 바꾸는 것이고 필터 자체를 고치는 것이
 * 아니다 — 그래서 처리 중인 메시지는 자기가 집어 든 필터로 끝까지 가고, 두 사전이 섞인 상태를
 * 보는 경로가 없다.
 */
@InMemoryChat
public interface ProfanityFilterInstaller {

    /** 다음 메시지부터 쓰일 필터. {@code null} 은 마스킹을 꺼뜨리므로 구현이 거부한다. */
    void install(ProfanityFilter filter);
}
