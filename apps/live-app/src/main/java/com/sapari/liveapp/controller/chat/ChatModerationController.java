package com.sapari.liveapp.controller.chat;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.sapari.chat.command.KickUserCommand;
import com.sapari.chat.command.ReconcileKickListCommand;
import com.sapari.chat.port.KickUserUseCase;
import com.sapari.chat.port.ReconcileKickListUseCase;
import com.sapari.liveapp.controller.chat.dto.KickUserRequest;
import com.sapari.liveapp.security.LiveUserPrincipal;

import lombok.RequiredArgsConstructor;

/**
 * 방송 중 채팅 통제 — 지금은 강퇴 하나다.
 *
 * <p><b>왜 이 앱인가.</b> 강퇴는 방 주인이 누구고 방이 진행 중인지를 물어야 하는데, 그 답을 주는 빈이
 * live-core에 있다. live-core를 얹은 앱은 룸 토큰 설정도 함께 스캔하고, 그 설정은 서명용 <b>개인키</b>를
 * 요구한다 — 다른 앱에 두면 그 키를 복제해야 한다. 게다가 이 요청을 보내는 판매자는 이미 이 앱과
 * 대화 중이다.
 *
 * <p><b>신원은 본문이 아니라 인증 주체에서 온다.</b> 강퇴자 id와 역할을 여기서 채우므로 요청 본문에는
 * 그 자리가 없다.
 *
 * <p>응답 본문이 없다. 성공은 204이고 상태는 {@code @ResponseStatus}가 준다 — {@code ResponseEntity}를
 * 상태 코드 때문에 쓰는 것은 이 저장소의 컨트롤러 규약 위반이다. 거부는 {@code ChatException}이 공통
 * 처리기를 거쳐 나간다 —
 * 권한 없음은 403, 끝난 방과 증거 불일치는 400, 같은 대상에 대한 처리가 겹쳐 상한을 넘으면 409다.
 * 넷을 더 뭉갤 필요는 없다: 이 구분이 보이는 건 <b>이미 그 방을 강퇴할 수 있는 사람</b>뿐이고,
 * 그 사람이 자기 방이 끝난 걸 아는 것은 유출이 아니다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/lives/{roomId}/chat")
public class ChatModerationController {

    private final KickUserUseCase kickUserUseCase;
    private final ReconcileKickListUseCase reconcileKickListUseCase;

    @PostMapping("/kick")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void kick(@PathVariable UUID roomId,
                     @RequestBody @Valid KickUserRequest request,
                     @AuthenticationPrincipal LiveUserPrincipal principal) {
        kickUserUseCase.kick(new KickUserCommand(
                roomId, principal.userId(), principal.role(),
                request.targetUserId(), request.messageId()));
    }

    /**
     * 강퇴 집행 명단을 정본에서 다시 세운다 — ADMIN 전용.
     *
     * <p><b>{@code PUT} 인 것이 계약이다.</b> 이 조치는 "정본과 같게 만든다" 이므로 몇 번을 불러도
     * 결과가 같다. {@code POST} 로 두면 "누적되는 조치" 로 읽히는데, 실제로는 명단을 통째로 교체한다.
     *
     * <p><b>본문이 없다.</b> 명단은 서버가 정본({@code chat_kick_log})에서 읽는다 — 호출자가 실어
     * 보내면 정본에 없는 사람을 집행 명단에 올릴 수 있고, 그러면 복구 수단이 감사 없는 강퇴 수단이 된다.
     *
     * <p>복원한 수를 돌려준다. 0 도 성공이다 — 그 방에 강퇴 기록이 없었다는 뜻이고, 그걸 404 로
     * 가르면 운영자가 "복구 실패" 와 "복구할 것이 없음" 을 구분할 수 없다.
     */
    @PutMapping("/kicked")
    public ReconcileKickListResponse reconcileKickList(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal LiveUserPrincipal principal) {
        int restored = reconcileKickListUseCase.reconcile(new ReconcileKickListCommand(
                roomId, principal.userId(), principal.role()));
        return new ReconcileKickListResponse(restored);
    }

    /** 복원한 사람 수. 운영자가 "복구할 것이 없었다" 와 "복구했다" 를 구분하는 유일한 값이다. */
    public record ReconcileKickListResponse(int restored) {
    }
}
