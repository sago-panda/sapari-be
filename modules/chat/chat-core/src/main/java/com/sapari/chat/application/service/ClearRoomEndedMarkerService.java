package com.sapari.chat.application.service;

import com.sapari.chat.command.ClearRoomEndedMarkerCommand;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.exception.LiveNotActiveException;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatRoomEndedWriteRepository;
import com.sapari.chat.port.ClearRoomEndedMarkerUseCase;
import com.sapari.live.port.GetLiveRoomUseCase;
import com.sapari.live.view.LiveRoomView;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 잘못 기록된 종료 마커를 해제한다 — <b>해제 경로가 곧 그 마커에 없던 진위 검증이다.</b>
 *
 * <p>정본 L20: "종료 마커에 진위 검증·해제 경로가 없다. 오발행 1건이 그 방을 TTL(30분)만큼 채팅
 * 불능으로 만든다." 두 결함이 하나로 묶여 있다 — chat 은 받은 Pub/Sub 신호가 진짜인지 확인할 수단이
 * 없고(페이로드에 그런 것이 없다) 지울 길도 없어서, 마커 보관 기간이 그대로 <b>오판의 회복 시간</b>이
 * 된다. 기록 어댑터가 로그에 {@code (해제: DEL ...)} 를 찍어 둔 것이 그 때문이고, 그게 지금까지의
 * 유일한 처방이었다.
 *
 * <p>오발행이 사람 실수만도 아니다 — 방치된 Live 를 정리하는 <b>배치도 같은 신호를 발행</b>하므로
 * 방 단위 오판이 자동으로 생길 수 있다.
 *
 * <p><b>그래서 이 서비스의 핵심은 지우는 것이 아니라 묻는 것이다.</b> chat 이 못 하는 진위 확인을
 * live 의 정본이 한다 — 그 방이 <b>실제로</b> 진행 중이면 받은 종료 신호가 거짓이었다는 뜻이고,
 * 끝난 방이면 마커가 맞으므로 거부한다. 이 검증이 있어서 해제 권한을 판매자까지 열 수 있다:
 * 자기 방이 진짜 진행 중일 때만 풀 수 있으므로, 끝난 방을 되살리는 데는 쓸 수 없다.
 *
 * <p><b>권한을 강퇴와 같은 모양으로 둔 이유.</b> 오발행으로 막히는 것은 판매자의 진행 중인 방송이고,
 * 그것을 초 단위로 알아채는 사람도 판매자다("채팅이 죽었다"). 운영자만 풀 수 있게 하면 가장 급한
 * 사람이 가장 느린 경로를 기다린다 — 강퇴 명단 재적재({@code ReconcileKickListService})에서
 * ADMIN 전용을 고른 것과 <b>반대 방향</b>인데, 그쪽은 신호가 운영자만 읽는 로그였고 이쪽은
 * 당사자가 즉시 아는 증상이다. 가청성이 권한을 정한다는 같은 기준의 다른 답이다.
 *
 * <p>⚠️ <b>이 해제는 세션을 되살리지 않는다.</b> 종료 신호를 받은 Pod 들은 이미 그 방 세션을 닫았다.
 * 마커를 지우면 <b>다시 들어올 수 있게</b> 되는 것이고, 재입장은 클라이언트가 한다. 그래서 응답이
 * "복구됐다" 가 아니라 "막혀 있지 않다" 다.
 */
@Slf4j
@RequiredArgsConstructor
public class ClearRoomEndedMarkerService implements ClearRoomEndedMarkerUseCase {

    private final GetLiveRoomUseCase liveRoomReader;
    private final ChatRoomEndedWriteRepository roomEndedWriteRepository;

    @Override
    public void clear(ClearRoomEndedMarkerCommand command) {
        ChatRole actorRole = ChatRole.fromPlatformRole(command.actorRole())
                .orElseThrow(() -> new ChatPermissionDeniedException(
                        "알 수 없는 역할이다 — role=" + command.actorRole()));

        // 방을 먼저 읽는다 — 권한 판정에 방 주인이 필요하다. 없는 방은 권한 거부로 접는다:
        // "없는 방" 과 "권한 없음" 을 가르면 응답 차이가 방 열거 오라클이 된다(강퇴와 같은 판단).
        LiveRoomView room = liveRoomReader.findRoom(command.roomId())
                .orElseThrow(() -> new ChatPermissionDeniedException(
                        "종료 마커를 해제할 수 없는 방이다 — roomId=" + command.roomId()));

        if (!room.sellerId().equals(command.actorId()) && actorRole != ChatRole.ADMIN) {
            throw new ChatPermissionDeniedException(
                    "종료 마커 해제 권한이 없다 — actor=" + command.actorId() + " room=" + command.roomId());
        }

        // 진위 검증. 이 줄이 이 엔드포인트의 존재 이유이자 안전장치다 — 끝난 방의 마커는 맞는
        // 마커이므로 지우면 안 된다. 이것을 빼면 "종료된 방을 다시 열 수 있는 엔드포인트" 가 된다.
        if (!room.live()) {
            throw new LiveNotActiveException(
                    "진행 중인 방이 아니라 종료 마커를 해제할 수 없다 — 그 마커는 맞는 마커다 roomId="
                            + command.roomId());
        }

        roomEndedWriteRepository.clearEnded(command.roomId());

        // 사람이 누른 조치이고, 기록 쪽이 "이 방이 언제 잠겼는지" 를 로그로만 남기므로 짝이 되게 적는다.
        log.info("방 종료 마커 해제 — 오발행된 신호였다. roomId={} actor={} (세션은 되살지 않는다, 재입장 필요)",
                command.roomId(), command.actorId());
    }
}
