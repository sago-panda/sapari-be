package com.sapari.streamingapp.websocket;

import org.springframework.stereotype.Component;

import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatKickRepository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * chat 소유 입장 게이트 — 룸 토큰 검증({@code RoomTokenVerifier}) 통과 후 실행되는 모더레이션 검사.
 *
 * <p>토큰은 "라이브 여부 + 신원 + owner"까지 담지만 enter <i>이후</i>의 강퇴/밴은 못 담는다. 그건 chat 소유
 * 상태(Redis)라 핸드셰이크에서 여기서 검사한다.
 *
 * <p><b>fail-open</b>: kicked 조회 실패(Redis 장애) 시 입장을 <i>허용</i>한다(가용성 우선 — 채팅 전면 불능이
 * 강퇴자 일시 통과보다 나쁜 결과). 정책 정본 L11/L13/TC#25·#26.
 *
 * <p>banned 검사는 ban 모델(#27) 구현 후 추가. 게스트(에페메랄 id)는 강퇴/밴 대상이 아니므로 건너뛴다.
 */
@Component
@RequiredArgsConstructor
public class EntryGate {

    private final ChatKickRepository kickRepository;

    public Mono<Void> verify(ChatSession session) {
        if (session.role() == ChatRole.GUEST) {
            return Mono.empty();
        }
        return kickRepository.isKicked(session.roomId(), session.userId())
                .onErrorReturn(false)   // fail-open(#50): 조회 불가 → 입장 허용
                .flatMap(kicked -> kicked
                        ? Mono.<Void>error(new EntryDeniedException(EntryDeniedException.Reason.KICKED))
                        : Mono.empty());
    }
}
