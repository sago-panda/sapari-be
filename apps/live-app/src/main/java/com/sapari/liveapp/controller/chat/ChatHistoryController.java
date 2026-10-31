package com.sapari.liveapp.controller.chat;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sapari.chat.command.GetChatHistoryCommand;
import com.sapari.chat.domain.exception.ChatHistoryQueryInvalidException;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.port.GetChatHistoryUseCase;
import com.sapari.chat.view.GetChatHistoryView;
import com.sapari.common.response.ResponseEnvelope;
import com.sapari.liveapp.security.LiveUserPrincipal;

import lombok.RequiredArgsConstructor;

/**
 * 다시보기 채팅 이력 조회.
 *
 * <p><b>공개 조회다</b> — 비회원도 다시보기 채팅을 본다(설계 확정). 그래서 principal 이 없을 수 있고,
 * 없으면 명령에 신원을 비워 보낸다. 신원이 없으면 특권 뷰(원문·발신자 이메일)를 받지 못할 뿐이고
 * 그 판정은 서비스가 한다 — 컨트롤러가 미리 좁히면 같은 판정이 두 곳에 생긴다.
 *
 * <p><b>신원은 본문·쿼리가 아니라 인증 주체에서 온다.</b> 요청자가 자기 id 를 보내게 하면 아무나
 * 방 주인 id 를 넣어 남의 방 구매자 이메일과 욕설 원문을 읽는다.
 *
 * <p>두 모드는 같은 경로에 산다 — 커서({@code beforeId})와 VOD 위치({@code playbackPositionMs}).
 * 동시 지정은 명령이 거부한다(한쪽을 조용히 무시하면 클라가 받은 결과가 자기 의도와 다른 것을
 * 알 수 없다). 그 거부는 {@code IllegalArgumentException} 인데 전역 핸들러에 그 타입 처리가 없어서,
 * 아래 {@code command} 가 받아 {@code CHAT-009}(400) 로 번역한다.
 *
 * <p>{@code size} 기본값 15 는 커서 모드 전용이다 — VOD 모드에서는 무시되고 200 고정이다
 * (프론트 가이드 계약). 기본값을 두는 이유는 스크롤 첫 페이지가 파라미터 없이 와도 되게 하는 것이다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/lives/{roomId}/chat")
public class ChatHistoryController {

    /** 커서 모드 기본 페이지 크기. 공용 페이징 기본값({@code PageSupport})과 같은 수다. */
    private static final int DEFAULT_SIZE = 15;

    private final GetChatHistoryUseCase getChatHistoryUseCase;

    @GetMapping("/history")
    public ResponseEnvelope<GetChatHistoryView> history(
            @PathVariable UUID roomId,
            @RequestParam(required = false) String beforeId,
            @RequestParam(required = false) Long playbackPositionMs,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size,
            @AuthenticationPrincipal LiveUserPrincipal principal) {

        return ResponseEnvelope.success(getChatHistoryUseCase.getHistory(
                command(roomId, beforeId, playbackPositionMs, size, principal)));
    }

    /**
     * 명령을 짓고, 계약 위반을 <b>400 으로 번역한다</b>.
     *
     * <p>명령 record 의 불변식은 {@code IllegalArgumentException} 으로 던지는데 전역 핸들러에 그 타입
     * 처리가 없어 그대로 두면 클라이언트 잘못이 500 + {@code Unhandled exception} 로그가 된다 —
     * 알림이 울리고 고칠 쪽은 서버가 아닌데 서버 장애로 보인다. 전역 핸들러를 넓히는 쪽은 저장소
     * 전체의 응답을 바꾸므로(인프라에서 올라온 같은 타입까지 400 이 된다) 여기서 좁게 받는다.
     *
     * <p>사유는 {@code debugMessage} 로만 간다 — 응답 문구는 {@code CHAT-009} 하나다. 셋 다 클라이언트
     * 코드의 버그라 사용자가 사유별로 다르게 할 일이 없고, 사유는 로그에 남는다.
     */
    private static GetChatHistoryCommand command(UUID roomId, String beforeId, Long playbackPositionMs,
            int size, LiveUserPrincipal principal) {
        try {
            return new GetChatHistoryCommand(
                    roomId,
                    principal == null ? null : principal.userId(),
                    principal == null ? null : toChatRole(principal.role()),
                    beforeId, playbackPositionMs, size);
        } catch (IllegalArgumentException e) {
            throw new ChatHistoryQueryInvalidException(e.getMessage(), e);
        }
    }

    /**
     * 플랫폼 역할(JWT {@code role} claim) → chat 역할.
     *
     * <p><b>두 열거가 다르다</b> — 플랫폼은 {@code USER}/{@code SELLER} 이고 chat 은 {@code BUYER}/
     * {@code SELLER}/{@code ADMIN}/{@code GUEST} 다. 겹치는 이름은 {@code SELLER} 하나뿐이라, 번역 없이
     * 넘기면 판매자만 지나가고 <b>일반 구매자는 소비자의 {@code ChatRole.valueOf} 에서 전부 실패한다</b>
     * — 응답은 500 이고 고칠 쪽은 서버다. 주 소비자 경로만 깨지는 모양이라 눈에 잘 띄지도 않는다.
     *
     * <p>번역이 컨트롤러에 있는 이유: 명령의 {@code requesterRole} 계약이 "chat 역할 이름" 이다. 번역을
     * 서비스로 밀면 chat 이 자기 경계 밖 열거를 알아야 하고, 소비자가 늘 때마다 그 지식이 복제된다.
     *
     * <p>모르는 값은 <b>거부한다</b>. 조용히 {@code BUYER} 로 떨어뜨리면 새 플랫폼 역할이 생겼을 때 그
     * 사용자가 자기 권한보다 좁은 뷰를 받고도 그 사실을 알 수 없다 — 버그로 보이지 않는 버그가 된다.
     */
    private static String toChatRole(String platformRole) {
        return switch (platformRole) {
            case "USER" -> ChatRole.BUYER.name();
            case "SELLER" -> ChatRole.SELLER.name();
            case "ADMIN" -> ChatRole.ADMIN.name();
            default -> throw new IllegalStateException(
                    "플랫폼 역할을 chat 역할로 번역할 수 없다 — role=" + platformRole);
        };
    }
}
