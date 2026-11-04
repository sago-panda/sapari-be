package com.sapari.liveapp.controller.chat;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.sapari.chat.command.ReleaseBanCommand;
import com.sapari.chat.port.ReleaseBanUseCase;
import com.sapari.liveapp.security.LiveUserPrincipal;

import lombok.RequiredArgsConstructor;

/**
 * 플랫폼 밴 관리 — <b>방 경로 밖</b>이다.
 *
 * <p>{@link ChatModerationController}와 갈라 두는 이유가 경로다. 강퇴는 방 안의 일이라
 * {@code /lives/{roomId}/chat} 아래가 맞지만, 밴은 사람에 붙어 어느 방에도 못 들어가게 하는
 * 상태다({@code ChatBan}). 방 경로 아래에 두면 {@code roomId}를 받아야 하고, 받으면 다음 사람이
 * "자기 방이면 풀 수 있다" 가지를 더하게 된다 — 그건 자동 승격이 요구한
 * <b>서로 다른 판매자 3·6·9명</b>의 독립된 판단을 한 명이 되돌리는 길이다.
 *
 * <p><b>신원은 본문이 아니라 인증 주체에서 온다.</b> 그래서 요청 본문이 없고, 해제자 id·역할을
 * 여기서 채운다.
 *
 * <p>성공은 204다. <b>풀 밴이 없어도 204</b>다 — 해제는 멱등이고, 그 성질에 기대는 재시도 경로가
 * 있다(정본은 지워졌는데 미러 삭제가 실패한 뒤). 404로 가르면 그 재시도가 미러에 도달하지 못한다.
 * 거부는 {@code ChatException}이 공통 처리기를 거쳐 403으로 나간다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/chat/bans")
public class ChatBanController {

    private final ReleaseBanUseCase releaseBanUseCase;

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable UUID userId,
                        @AuthenticationPrincipal LiveUserPrincipal principal) {
        releaseBanUseCase.release(new ReleaseBanCommand(userId, principal.userId(), principal.role()));
    }
}
