package com.sapari.streamingapp.websocket;

import org.springframework.stereotype.Component;

import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatKickRepository;
import com.sapari.chat.domain.repository.ChatRoomEndedRepository;

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
    private final ChatRoomEndedRepository roomEndedRepository;

    public Mono<Void> verify(ChatSession session) {
        // 방 종료는 게스트에게도 적용된다 — 끝난 방은 볼 것도 없다. 강퇴/밴만 게스트 대상이 아니다.
        return verifyRoomAlive(session)
                .then(Mono.defer(() -> verifyNotKicked(session)));
    }

    /**
     * 종료된 방에는 들어갈 수 없다.
     *
     * <p>룸 토큰은 발급 시점에 라이브였다는 사실만 담는다. 종료 직전에 토큰을 받은 사람은 세션이 닫힌 뒤에도
     * 만료 전까지 다시 붙을 수 있어서, 종료 이벤트로 남긴 마커를 여기서 읽는다.
     */
    private Mono<Void> verifyRoomAlive(ChatSession session) {
        return roomEndedRepository.isEnded(session.roomId())
                .onErrorReturn(false)   // 강퇴 조회와 같은 fail-open — 조회 불가로 채팅을 막지 않는다
                .flatMap(ended -> ended
                        ? Mono.<Void>error(new EntryDeniedException(EntryDeniedException.Reason.ROOM_ENDED))
                        : Mono.empty());
    }

    private Mono<Void> verifyNotKicked(ChatSession session) {
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
