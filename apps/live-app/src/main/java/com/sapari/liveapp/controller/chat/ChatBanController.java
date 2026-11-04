package com.sapari.liveapp.controller.chat;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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
    public ReleaseBanResponse release(@PathVariable UUID userId,
                                      @AuthenticationPrincipal LiveUserPrincipal principal) {
        var result = releaseBanUseCase.release(
                new ReleaseBanCommand(userId, principal.userId(), principal.role()));
        return new ReleaseBanResponse(result.banExisted(), result.wasPermanent());
    }

    /**
     * 무엇을 풀었는가. <b>204 가 아니라 200 + 본문인 이유가 이것이다.</b>
     *
     * <p>처음에 204 로 뒀는데, 그러면 "활성 영구 밴을 풀었다" 와 "밴 없는 사람에게 버튼을 눌렀다"
     * 가 누른 사람에게 <b>같은 응답</b>이다. 응답이 제3자의 존재 여부를 누설하지 않는 쪽은 맞지만
     * — 그래서 없는 사용자에게도 성공을 준다 — 누른 사람에게도 신호가 0 이면 다른 문제가 생긴다:
     * 실존하는 다른 사용자 UUID 를 한 글자 틀리면 그 사람의 730일 누적이 조용히 접히고,
     * {@code chat_ban_release} 가 append-only 라 앱 경로로 기준선을 낮출 수도 없다.
     *
     * <p>{@code wasPermanent} 를 따로 주는 이유: 영구 밴은 <b>수동 밴 경로가 0건</b>이라 앱으로
     * 다시 걸 수 없다. 방금 되돌릴 수 없는 일을 했다는 사실이 그 자리에서 보여야 한다.
     *
     * <p>발급자 id 는 싣지 않는다 — 제3자 식별자다. 그 감사는 {@code chat_ban_release} 행이 든다.
     */
    public record ReleaseBanResponse(boolean banExisted, boolean wasPermanent) {
    }
}
