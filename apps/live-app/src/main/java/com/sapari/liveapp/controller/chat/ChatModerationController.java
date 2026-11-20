package com.sapari.liveapp.controller.chat;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.sapari.chat.command.KickUserCommand;
import com.sapari.chat.command.ClearRoomEndedMarkerCommand;
import com.sapari.common.response.ResponseEnvelope;
import com.sapari.chat.command.ReconcileKickListCommand;
import com.sapari.chat.port.KickUserUseCase;
import com.sapari.chat.port.ClearRoomEndedMarkerUseCase;
import com.sapari.chat.port.ReconcileKickListUseCase;
import com.sapari.liveapp.controller.chat.dto.KickUserRequest;
import com.sapari.liveapp.security.LiveUserPrincipal;

import lombok.RequiredArgsConstructor;

/**
 * 방송 중 채팅 통제 — 셋이다: 강퇴 · 강퇴 명단 재적재 · 종료 마커 해제.
 *
 * <p><b>왜 이 앱인가.</b> 강퇴는 방 주인이 누구고 방이 진행 중인지를 물어야 하는데, 그 답을 주는 빈이
 * live-core에 있다. live-core를 얹은 앱은 룸 토큰 설정도 함께 스캔하고, 그 설정은 서명용 <b>개인키</b>를
 * 요구한다 — 다른 앱에 두면 그 키를 복제해야 한다. 게다가 이 요청을 보내는 판매자는 이미 이 앱과
 * 대화 중이다.
 *
 * <p><b>신원은 본문이 아니라 인증 주체에서 온다.</b> 강퇴자 id와 역할을 여기서 채우므로 요청 본문에는
 * 그 자리가 없다.
 *
 * <p>강퇴와 마커 해제는 응답 본문이 없다(성공 204). 재적재만 봉투를 돌려준다 — 복원 수와
 * 오염 여부가 운영자에게 가야 하기 때문이다. 본문 없는 쪽의 상태는 {@code @ResponseStatus}가
 * 준다 — {@code ResponseEntity}를
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
    private final ClearRoomEndedMarkerUseCase clearRoomEndedMarkerUseCase;

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
     * 결과가 같다. {@code POST} 로 두면 "누적되는 조치" 로 읽힌다.
     *
     * <p>⚠️ 한때 그 뒤에 "실제로는 명단을 통째로 교체한다" 가 붙어 있었다. <b>폐기된 설계다</b> —
     * 그 문장은 {@code DEL} 로 시작하던 판({@code 9ae1e3e1})에서 왔고, 그 판이 동시 강퇴를
     * 영구히 지우는 결함이라 {@code 2c18bffd} 가 <b>더하기</b>로 뒤집었다. 지금 어댑터는 건강한
     * 키에는 더하기만 하고 오염된 키만 지우고 다시 세운다({@code ChatKickWriteRepository} 의
     * 세 줄 계약). 그 문장을 남겨 두면 다음 사람이 "교체" 를 전제로 {@code DEL} 을 되살린다.
     *
     * <p><b>본문이 없다.</b> 명단은 서버가 정본({@code chat_kick_log})에서 읽는다 — 호출자가 실어
     * 보내면 정본에 없는 사람을 집행 명단에 올릴 수 있고, 그러면 복구 수단이 감사 없는 강퇴 수단이 된다.
     *
     * <p>복원한 수를 돌려준다. 0 도 성공이다 — 그 방에 강퇴 기록이 없었다는 뜻이고, 그걸 404 로
     * 가르면 운영자가 "복구 실패" 와 "복구할 것이 없음" 을 구분할 수 없다.
     */
    @PutMapping("/kicked")
    public ResponseEnvelope<ReconcileKickListResponse> reconcileKickList(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal LiveUserPrincipal principal) {
        var outcome = reconcileKickListUseCase.reconcile(new ReconcileKickListCommand(
                roomId, principal.userId(), principal.role()));
        return ResponseEnvelope.success(
                new ReconcileKickListResponse(outcome.restored(), outcome.wasCorrupted()));
    }

    /**
     * 잘못 기록된 종료 마커를 해제한다 — 방 주인과 관리자.
     *
     * <p><b>끝난 방은 거부된다</b>(409 가 아니라 400 — {@code LiveNotActiveException}). 해제는
     * 그 방이 live 정본에서 <b>실제로</b> 진행 중일 때만 되고, 그 검증이 이 엔드포인트의 존재
     * 이유다: chat 은 받은 종료 신호의 진위를 확인할 수단이 없지만 이 앱은 정본을 읽는다.
     * 그 검증이 있어서 판매자에게 열 수 있다 — 끝난 방을 되살리는 데는 쓸 수 없다.
     *
     * <p>성공은 204 다. <b>세션을 되살리지 않는다</b> — 종료 신호를 받은 Pod 들은 이미 그 방
     * 세션을 닫았고, 이 호출이 하는 일은 "다시 들어올 수 있게" 하는 것이다. 재입장은 클라이언트가 한다.
     */
    @DeleteMapping("/ended")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearRoomEndedMarker(@PathVariable UUID roomId,
                                     @AuthenticationPrincipal LiveUserPrincipal principal) {
        clearRoomEndedMarkerUseCase.clear(new ClearRoomEndedMarkerCommand(
                roomId, principal.userId(), principal.role()));
    }

    /**
     * 복원한 사람 수와 <b>고치기 전 오염 여부</b>.
     *
     * <p>{@code restored} 는 운영자가 "복구할 것이 없었다" 와 "복구했다" 를 구분하는 값이고,
     * {@code wasCorrupted} 는 <b>조치가 실제로 필요했는지</b>를 그 자리에서 보여 준다 —
     * 키가 타입이 틀려 있었다는 것은 그 방의 강퇴 집행이 꺼져 있었다는 직접 증거다.
     * {@code DEL} 뒤에는 알 수 없는 사실이라 어댑터가 미리 읽어 넘긴다.
     */
    public record ReconcileKickListResponse(int restored, boolean wasCorrupted) {
    }
}
