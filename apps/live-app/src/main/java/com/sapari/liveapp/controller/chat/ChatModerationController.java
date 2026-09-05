package com.sapari.liveapp.controller.chat;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sapari.chat.command.KickUserCommand;
import com.sapari.chat.port.KickUserUseCase;
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
 * <p>응답 본문이 없다. 성공은 204이고, 거부는 {@code ChatException}이 공통 처리기를 거쳐 나간다 —
 * 권한 없음은 403, 끝난 방과 증거 불일치는 400이다. 셋을 더 뭉갤 필요는 없다: 이 구분이 보이는 건
 * <b>이미 그 방을 강퇴할 수 있는 사람</b>뿐이고, 그 사람이 자기 방이 끝난 걸 아는 것은 유출이 아니다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/lives/{roomId}/chat")
public class ChatModerationController {

    private final KickUserUseCase kickUserUseCase;

    @PostMapping("/kick")
    public ResponseEntity<Void> kick(@PathVariable UUID roomId,
                                     @RequestBody @Valid KickUserRequest request,
                                     @AuthenticationPrincipal LiveUserPrincipal principal) {
        kickUserUseCase.kick(new KickUserCommand(
                roomId, principal.userId(), principal.role(),
                request.targetUserId(), request.messageId()));
        return ResponseEntity.noContent().build();
    }
}
